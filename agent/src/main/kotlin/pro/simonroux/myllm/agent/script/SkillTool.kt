package pro.simonroux.myllm.agent.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import pro.simonroux.myllm.agent.Tool
import pro.simonroux.myllm.agent.ToolContext
import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillPermission
import pro.simonroux.myllm.core.model.ToolResult
import pro.simonroux.myllm.core.model.ToolSource
import pro.simonroux.myllm.core.model.ToolSpec

/**
 * A skill, presented to the model as an ordinary tool.
 *
 * From the agent loop's point of view there is no difference between this and a
 * compiled tool, which is the point: a tool the model wrote five minutes ago is
 * called exactly like one that shipped with the app.
 */
class SkillTool(
    val skill: Skill,
    private val sandbox: ScriptSandbox = ScriptSandbox(),
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = skill.name,
        description = skill.description,
        parameters = skill.parameters,
        source = ToolSource.SKILL,
        // Anything that can leave the device or write to it is worth a prompt
        // the first time, even when the user trusts the skill.
        requiresConfirmation = SkillPermission.NETWORK in skill.permissions ||
            SkillPermission.STORAGE in skill.permissions,
    )

    /** Lines the last run wrote through host.log, for the skill editor. */
    var lastRunLog: List<String> = emptyList()
        private set

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            val log = mutableListOf<String>()

            val bridge = SkillBridge(skill, context.host) { line -> log += line }

            val result = sandbox.execute(
                code = skill.code,
                argumentsJson = arguments.toString(),
                timeoutMs = skill.timeoutMs,
                bridge = bridge::handle,
            )

            lastRunLog = log

            when (result) {
                is ScriptResult.Success -> ToolResult(
                    callId = "",
                    name = skill.name,
                    ok = true,
                    content = result.json,
                    durationMs = System.currentTimeMillis() - startedAt,
                )

                is ScriptResult.Failure -> ToolResult(
                    callId = "",
                    name = skill.name,
                    ok = false,
                    content = buildString {
                        append("La skill a échoué : ").append(result.message)
                        if (log.isNotEmpty()) {
                            append("\nJournal :\n")
                            log.takeLast(10).forEach { append("  ").append(it).append('\n') }
                        }
                    },
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
}
