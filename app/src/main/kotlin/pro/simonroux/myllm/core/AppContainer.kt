package pro.simonroux.myllm.core

import android.content.Context
import pro.simonroux.myllm.BuildConfig
import pro.simonroux.myllm.agent.AgentLoop
import pro.simonroux.myllm.agent.ToolRegistry
import pro.simonroux.myllm.agent.script.ScriptSandbox
import pro.simonroux.myllm.agent.script.SkillTool
import pro.simonroux.myllm.agent.tools.BuiltInTools
import pro.simonroux.myllm.agent.tools.SkillAdminTools
import pro.simonroux.myllm.agent.ToolHost
import pro.simonroux.myllm.core.data.DataModule
import pro.simonroux.myllm.core.data.repo.ModelCatalog
import pro.simonroux.myllm.core.model.GenerationEvent
import pro.simonroux.myllm.core.model.GenerationParams
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.engine.api.CompletionRequest
import pro.simonroux.myllm.update.Updater

/**
 * Everything the app is made of, wired by hand.
 *
 * No dependency injection framework on purpose. The graph is small enough to
 * read in one sitting, the wiring is ordinary Kotlin that a model can edit
 * safely, and there is no annotation processor whose generated code fails a
 * build for reasons that are invisible in the source. That matters more than
 * usual in a codebase meant to rewrite itself.
 */
class AppContainer(private val context: Context) {

    private val data = DataModule(context, BuildConfig.VERSION_NAME)

    // Getters rather than vals: reading a val here would construct every
    // repository, and the database with them, while Application.onCreate is
    // still on the main thread.
    val settingsStore get() = data.settings
    val secretStore get() = data.secrets
    val chatRepository get() = data.chat
    val skillRepository get() = data.skills
    val noteRepository get() = data.notes
    val modelRepository get() = data.models
    val modelDownloader get() = data.downloader
    val changeRequestRepository get() = data.changeRequests

    val engineManager: EngineManager by lazy {
        EngineManager(settingsStore, secretStore, modelRepository)
    }

    val updater: Updater by lazy { Updater(context, settingsStore) }

    val sandbox: ScriptSandbox by lazy { ScriptSandbox() }

    val toolRegistry: ToolRegistry by lazy { ToolRegistry() }

    val toolHost: ToolHost by lazy {
        data.createToolHost(
            offlineOnly = { settingsStore.current().offlineOnly },
            subCompletion = ::subCompletion,
        )
    }

    val agentLoop: AgentLoop by lazy { AgentLoop(toolRegistry, toolHost) }

    private val skillAdminTools: SkillAdminTools by lazy {
        SkillAdminTools(
            store = skillRepository,
            sandbox = sandbox,
            requireConfirmation = { requireSelfModificationConfirmation },
            onChanged = { refreshSkillTools() },
        )
    }

    /**
     * Cached because the tool specs are rebuilt on every skill change and the
     * setting is read on the hot path of building a prompt.
     */
    @Volatile
    private var requireSelfModificationConfirmation: Boolean = true

    /**
     * Brings the container up to a usable state.
     *
     * Called once from Application.onCreate. Everything here is either cheap or
     * must happen before the first screen can be correct, so nothing is deferred
     * to first use.
     */
    suspend fun initialise() {
        val settings = settingsStore.current()
        requireSelfModificationConfirmation = settings.agent.confirmSelfModification

        modelRepository.seedCatalog(ModelCatalog.suggestions)
        modelRepository.reconcile()

        BuiltInTools.all(noteRepository, changeRequestRepository)
            .forEach { toolRegistry.registerBuiltIn(it) }

        if (settings.agent.selfModificationEnabled) {
            skillAdminTools.all().forEach { toolRegistry.registerBuiltIn(it) }
        }

        refreshSkillTools()
    }

    /** Rebuilds the skill-backed tools from what is enabled in the database. */
    suspend fun refreshSkillTools() {
        val tools = skillRepository.enabled().map { SkillTool(it, sandbox) }
        toolRegistry.replaceSkills(tools)
    }

    suspend fun onSettingsChanged() {
        requireSelfModificationConfirmation =
            settingsStore.current().agent.confirmSelfModification
    }

    /**
     * A one-shot completion for skills calling host.ask.
     *
     * Deliberately not agentic: a skill that could trigger the full tool loop
     * could call itself, and a recursion whose base case depends on a model's
     * judgement is not one worth having.
     */
    private suspend fun subCompletion(prompt: String, maxTokens: Int): String {
        val engine = when (val acquired = engineManager.acquire()) {
            is Outcome.Ok -> acquired.value
            is Outcome.Err -> return "Aucun moteur disponible : ${acquired.error.message}"
        }

        val request = CompletionRequest(
            messages = listOf(
                pro.simonroux.myllm.core.model.ChatMessage(
                    id = "sub",
                    conversationId = "sub",
                    role = pro.simonroux.myllm.core.model.ChatRole.USER,
                    content = prompt,
                    createdAt = System.currentTimeMillis(),
                ),
            ),
            systemPrompt = "Réponds de façon brève et directe. Pas de préambule.",
            params = GenerationParams.PRECISE.copy(maxTokens = maxTokens),
        )

        val answer = StringBuilder()
        engine.generate(request).collect { event ->
            when (event) {
                is GenerationEvent.Token -> answer.append(event.text)
                is GenerationEvent.Failed -> answer.append("[erreur : ${event.error}]")
                else -> Unit
            }
        }
        return answer.toString()
    }
}
