package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable

/**
 * Everything the user can tune. Persisted as one object so a settings change is
 * atomic and so the whole configuration can be exported and re-imported.
 */
@Serializable
data class AppSettings(
    /** Master switch. When true no component may open a socket, whatever else is configured. */
    val offlineOnly: Boolean = false,
    val routingPolicy: RoutingPolicy = RoutingPolicy.LOCAL_FIRST,
    val activeEngineId: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val generation: GenerationParams = GenerationParams(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val agent: AgentSettings = AgentSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    /** Only download model weights while on an unmetered network. */
    val downloadOnWifiOnly: Boolean = true,
    val keepScreenOnWhileGenerating: Boolean = true,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Tu es l'assistant personnel de Simon, embarqué sur son téléphone. " +
                "Tu fonctionnes hors ligne par défaut. " +
                "Réponds de façon directe et factuelle, sans remplissage. " +
                "Quand une tâche demande un outil, appelle-le au lieu de deviner. " +
                "Quand il te manque un outil, tu peux en écrire un."
    }
}

@Serializable
data class AgentSettings(
    val enabled: Boolean = true,
    /** Hard ceiling on tool-call rounds per user turn, so a loop cannot run away. */
    val maxToolRounds: Int = 8,
    /** Ask before running any tool, not just the ones flagged as sensitive. */
    val confirmEveryTool: Boolean = false,
    /** Let the agent create and edit its own skills. */
    val selfModificationEnabled: Boolean = true,
    /** Ask before the agent writes or deletes a skill. Off means it edits itself unattended. */
    val confirmSelfModification: Boolean = true,
    val skillTimeoutMs: Long = 5_000,
)

@Serializable
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colour extraction. Off gives the app's own palette. */
    val dynamicColor: Boolean = true,
    val fontScale: Float = 1.0f,
    val showTokenStats: Boolean = true,
    val renderMarkdown: Boolean = true,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

@Serializable
data class UpdateSettings(
    val checkAutomatically: Boolean = true,
    /** owner/repo the updater watches for releases. */
    val repository: String = "simonroux-pro/my_llm",
    val includePrereleases: Boolean = false,
    val lastCheckedAt: Long = 0,
)
