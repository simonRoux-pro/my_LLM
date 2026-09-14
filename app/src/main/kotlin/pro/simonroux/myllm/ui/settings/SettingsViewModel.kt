package pro.simonroux.myllm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.EngineDescriptor
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.RemoteEngineConfig
import pro.simonroux.myllm.core.model.RoutingPolicy
import pro.simonroux.myllm.core.model.ThemeMode

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val remote = container.settingsStore.currentEngines()
                .firstOrNull { it.kind == EngineKind.REMOTE }
            _state.update {
                it.copy(
                    remoteBaseUrl = remote?.remote?.baseUrl.orEmpty(),
                    remoteModelId = remote?.remote?.modelId.orEmpty(),
                    hasRemoteKey = container.secretStore.has(REMOTE_KEY_ALIAS),
                )
            }
        }
    }

    // --- privacy and routing ---------------------------------------------

    fun setOfflineOnly(value: Boolean) = edit { it.copy(offlineOnly = value) }

    fun setRoutingPolicy(policy: RoutingPolicy) = edit { it.copy(routingPolicy = policy) }

    // --- prompting and generation ----------------------------------------

    fun setSystemPrompt(prompt: String) = edit { it.copy(systemPrompt = prompt) }
        .also { message("Prompt système enregistré") }

    fun setTemperature(value: Float) =
        edit { it.copy(generation = it.generation.copy(temperature = value)) }

    fun setMaxTokens(value: Int) =
        edit { it.copy(generation = it.generation.copy(maxTokens = value)) }

    fun setContextSize(value: Int) =
        edit { it.copy(runtime = it.runtime.copy(contextSize = value)) }

    // --- agent -------------------------------------------------------------

    fun setAgentEnabled(value: Boolean) = edit { it.copy(agent = it.agent.copy(enabled = value)) }

    fun setSelfModification(value: Boolean) =
        edit { it.copy(agent = it.agent.copy(selfModificationEnabled = value)) }
            .also { message("Redémarre l'app pour appliquer ce changement aux outils exposés.") }

    fun setConfirmSelfModification(value: Boolean) =
        edit { it.copy(agent = it.agent.copy(confirmSelfModification = value)) }

    fun setConfirmEveryTool(value: Boolean) =
        edit { it.copy(agent = it.agent.copy(confirmEveryTool = value)) }

    // --- appearance --------------------------------------------------------

    fun setTheme(mode: ThemeMode) = edit { it.copy(appearance = it.appearance.copy(theme = mode)) }

    fun setDynamicColor(value: Boolean) =
        edit { it.copy(appearance = it.appearance.copy(dynamicColor = value)) }

    fun setShowTokenStats(value: Boolean) =
        edit { it.copy(appearance = it.appearance.copy(showTokenStats = value)) }

    // --- remote endpoint ---------------------------------------------------

    /**
     * Stores a remote endpoint, and its key separately.
     *
     * An empty key field means "leave what is stored alone" rather than "clear
     * it", so re-saving the URL does not silently wipe the credential.
     */
    fun saveRemoteEndpoint(baseUrl: String, modelId: String, apiKey: String) {
        viewModelScope.launch {
            if (apiKey.isNotBlank()) {
                container.secretStore.put(REMOTE_KEY_ALIAS, apiKey.trim())
            }

            val descriptor = EngineDescriptor(
                id = REMOTE_ENGINE_ID,
                label = modelId.substringAfterLast('/').ifBlank { "Distant" },
                kind = EngineKind.REMOTE,
                priority = 50,
                remote = RemoteEngineConfig(
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    modelId = modelId.trim(),
                    apiKeyAlias = REMOTE_KEY_ALIAS,
                ),
            )

            container.settingsStore.updateEngines { engines ->
                engines.filterNot { it.id == REMOTE_ENGINE_ID } + descriptor
            }

            _state.update {
                it.copy(
                    remoteBaseUrl = descriptor.remote?.baseUrl.orEmpty(),
                    remoteModelId = descriptor.remote?.modelId.orEmpty(),
                    hasRemoteKey = container.secretStore.has(REMOTE_KEY_ALIAS),
                    message = "Endpoint enregistré",
                )
            }
        }
    }

    fun clearRemoteKey() {
        viewModelScope.launch {
            container.secretStore.remove(REMOTE_KEY_ALIAS)
            _state.update { it.copy(hasRemoteKey = false, message = "Clé effacée") }
        }
    }

    // --- data ---------------------------------------------------------------

    fun deleteAllConversations() {
        viewModelScope.launch {
            container.chatRepository.deleteEverything()
            message("Conversations effacées")
        }
    }

    fun clearNotes() {
        viewModelScope.launch {
            container.noteRepository.clear()
            message("Mémoire vidée")
        }
    }

    private fun edit(transform: (pro.simonroux.myllm.core.model.AppSettings) -> pro.simonroux.myllm.core.model.AppSettings) {
        viewModelScope.launch {
            container.settingsStore.update(transform)
            container.onSettingsChanged()
        }
    }

    private fun message(text: String) = _state.update { it.copy(message = text) }

    private companion object {
        const val REMOTE_ENGINE_ID = "remote:primary"
        const val REMOTE_KEY_ALIAS = "remote_primary_api_key"
    }
}

data class SettingsUiState(
    val remoteBaseUrl: String = "",
    val remoteModelId: String = "",
    val hasRemoteKey: Boolean = false,
    val message: String? = null,
)
