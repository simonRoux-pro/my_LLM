package pro.simonroux.myllm.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.simonroux.myllm.core.model.AgentSettings
import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.ChatRole
import pro.simonroux.myllm.core.model.FinishReason
import pro.simonroux.myllm.core.model.GenerationEvent
import pro.simonroux.myllm.core.model.GenerationParams
import pro.simonroux.myllm.core.model.MessageMeta
import pro.simonroux.myllm.core.model.ToolCall
import pro.simonroux.myllm.core.model.ToolResult
import pro.simonroux.myllm.engine.api.CompletionRequest
import pro.simonroux.myllm.engine.api.LlmEngine
import java.util.UUID

/**
 * One user turn, possibly spanning several model calls with tool execution in between.
 *
 * The loop is engine-agnostic: when the engine has a real tool-calling channel
 * it is used, and when it does not (any local GGUF) the same contract is
 * emulated by describing the tools in the prompt and parsing the reply. Callers
 * see identical events either way.
 *
 * [maxToolRounds] is a hard stop, not a suggestion. A model that keeps calling
 * the same tool would otherwise drain the battery in a loop no one is watching.
 */
class AgentLoop(
    private val registry: ToolRegistry,
    private val host: ToolHost,
) {

    fun run(
        engine: LlmEngine,
        conversationId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
        params: GenerationParams,
        settings: AgentSettings,
        /** Returns false to refuse a call. Invoked only for tools that ask for it. */
        confirm: suspend (ToolCall) -> Boolean = { true },
    ): Flow<AgentEvent> = flow {

        val specs = if (settings.enabled) registry.specs() else emptyList()
        val nativeTools = engine.capabilities.supportsTools && specs.isNotEmpty()

        val effectiveSystemPrompt = when {
            specs.isEmpty() -> systemPrompt
            nativeTools -> systemPrompt
            else -> systemPrompt + TextToolCall.buildPromptSection(specs)
        }

        val working = history.toMutableList()
        var lastMeta = MessageMeta()

        for (round in 0 until settings.maxToolRounds.coerceAtLeast(1)) {
            currentCoroutineContext().ensureActive()

            val request = CompletionRequest(
                messages = working.toList(),
                systemPrompt = effectiveSystemPrompt,
                params = params,
                tools = if (nativeTools) specs else emptyList(),
            )

            val text = StringBuilder()
            val thinking = StringBuilder()
            val structuredCalls = mutableListOf<ToolCall>()
            var failed: String? = null

            engine.generate(request).collect { event ->
                when (event) {
                    is GenerationEvent.PromptProcessed ->
                        emit(AgentEvent.PromptIngested(event.tokens, event.elapsedMs))

                    is GenerationEvent.Token -> {
                        text.append(event.text)
                        // Hold tokens back once a tool-call block opens: the user
                        // wants the result, not the protocol.
                        if (!TextToolCall.hasOpenCall(text.toString())) {
                            emit(AgentEvent.Token(event.text))
                        }
                    }

                    is GenerationEvent.Thinking -> {
                        thinking.append(event.text)
                        emit(AgentEvent.Thinking(event.text))
                    }

                    is GenerationEvent.ToolCallRequested -> structuredCalls += event.call

                    is GenerationEvent.Completed -> lastMeta = event.meta

                    is GenerationEvent.Failed -> failed = event.error
                }
            }

            failed?.let {
                emit(AgentEvent.Failed(it))
                return@flow
            }

            val parsed = if (structuredCalls.isNotEmpty()) {
                ParsedToolCalls(structuredCalls, text.toString().trim())
            } else {
                TextToolCall.parse(text.toString())
            }

            val assistantMessage = ChatMessage(
                id = newId(),
                conversationId = conversationId,
                role = ChatRole.ASSISTANT,
                content = parsed.text,
                createdAt = System.currentTimeMillis(),
                toolCalls = parsed.calls,
                thinking = thinking.toString().takeIf { it.isNotBlank() },
                meta = lastMeta,
            )
            working += assistantMessage

            if (parsed.calls.isEmpty()) {
                emit(AgentEvent.Finished(assistantMessage, working.toList()))
                return@flow
            }

            emit(AgentEvent.AssistantTurn(assistantMessage))

            for (call in parsed.calls) {
                currentCoroutineContext().ensureActive()
                val result = invoke(call, conversationId, settings, confirm)
                emit(AgentEvent.ToolFinished(call, result))

                working += ChatMessage(
                    id = newId(),
                    conversationId = conversationId,
                    role = ChatRole.TOOL,
                    content = result.content,
                    createdAt = System.currentTimeMillis(),
                    toolCallId = call.id,
                )
            }
        }

        // Every round was spent on tool calls. Rather than silently truncating,
        // say so: it usually means a tool is returning something the model
        // cannot act on.
        emit(
            AgentEvent.Finished(
                ChatMessage(
                    id = newId(),
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = "Arrêt après ${settings.maxToolRounds} tours d'outils sans réponse finale.",
                    createdAt = System.currentTimeMillis(),
                    meta = lastMeta.copy(finishReason = FinishReason.LENGTH),
                ),
                working.toList(),
            ),
        )
    }

    private suspend fun invoke(
        call: ToolCall,
        conversationId: String,
        settings: AgentSettings,
        confirm: suspend (ToolCall) -> Boolean = { true },
    ): ToolResult {
        val tool = registry.find(call.name)
            ?: return ToolResult(
                callId = call.id,
                name = call.name,
                ok = false,
                content = "Outil inconnu : ${call.name}. Outils disponibles : ${registry.names().joinToString()}",
            )

        val needsConfirmation = tool.spec.requiresConfirmation || settings.confirmEveryTool
        if (needsConfirmation && !confirm(call)) {
            return ToolResult(
                callId = call.id,
                name = call.name,
                ok = false,
                content = "Appel refusé par l'utilisateur.",
            )
        }

        val arguments = runCatching { call.arguments.jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val startedAt = System.currentTimeMillis()

        return runCatching {
            // Tools do not know the id of the call that reached them; stamping it
            // here keeps every implementation from having to thread it through.
            tool.execute(arguments, ToolContext(conversationId, host))
                .copy(callId = call.id, name = call.name)
        }.getOrElse { throwable ->
            ToolResult(
                callId = call.id,
                name = call.name,
                ok = false,
                content = "Erreur : ${throwable.message ?: throwable::class.simpleName}",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

sealed interface AgentEvent {

    data class PromptIngested(val tokens: Int, val elapsedMs: Long) : AgentEvent

    data class Token(val text: String) : AgentEvent

    data class Thinking(val text: String) : AgentEvent

    /** An assistant turn that asked for tools rather than answering. */
    data class AssistantTurn(val message: ChatMessage) : AgentEvent

    data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvent

    /** The final answer, plus every message produced during the turn. */
    data class Finished(val message: ChatMessage, val transcript: List<ChatMessage>) : AgentEvent

    data class Failed(val error: String) : AgentEvent
}
