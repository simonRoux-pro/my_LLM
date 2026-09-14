package pro.simonroux.myllm.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.data.repo.DownloadProgress
import pro.simonroux.myllm.core.model.EngineDescriptor
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.LocalEngineConfig
import pro.simonroux.myllm.core.model.LocalModel

/**
 * Model downloads and which one is live.
 *
 * Downloads are keyed by model id so several can be paused and resumed
 * independently, and they live in the ViewModel scope rather than a composable's
 * so switching tabs mid-download does not cancel it.
 */
class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private val downloads = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            container.modelRepository.observeAll().collect { models ->
                _state.update {
                    it.copy(
                        models = models,
                        freeSpaceBytes = container.modelRepository.freeSpaceBytes(),
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settingsStore.settings.collect { settings ->
                val engines = container.settingsStore.currentEngines()
                val active = engines.firstOrNull { it.id == settings.activeEngineId }
                _state.update { it.copy(activeModelId = active?.local?.modelId) }
            }
        }
    }

    fun download(model: LocalModel) {
        if (downloads[model.id]?.isActive == true) return

        downloads[model.id] = viewModelScope.launch {
            val settings = container.settingsStore.current()
            if (settings.offlineOnly) {
                _state.update {
                    it.copy(error = "Mode hors ligne actif : désactive-le pour télécharger.")
                }
                return@launch
            }

            container.modelDownloader.download(model).collect { progress ->
                when (progress) {
                    is DownloadProgress.Failed ->
                        _state.update { it.copy(error = progress.message) }

                    is DownloadProgress.Completed -> {
                        _state.update { it.copy(error = null) }
                        // A freshly downloaded model with nothing else configured
                        // is almost certainly the one the user wants running.
                        if (_state.value.activeModelId == null) activate(model)
                    }

                    else -> Unit
                }
            }
        }
    }

    fun cancel(model: LocalModel) {
        downloads.remove(model.id)?.cancel()
    }

    /** Points the router at this model, creating its engine entry if needed. */
    fun activate(model: LocalModel) {
        viewModelScope.launch {
            val engineId = "local:${model.id}"

            container.settingsStore.updateEngines { engines ->
                if (engines.any { it.id == engineId }) {
                    engines
                } else {
                    engines + EngineDescriptor(
                        id = engineId,
                        label = model.displayName,
                        kind = EngineKind.LOCAL,
                        priority = 10,
                        local = LocalEngineConfig(modelId = model.id),
                    )
                }
            }

            container.settingsStore.update { it.copy(activeEngineId = engineId) }
            _state.update { it.copy(activeModelId = model.id) }
        }
    }

    fun delete(model: LocalModel) {
        viewModelScope.launch {
            cancel(model)
            if (_state.value.activeModelId == model.id) {
                container.engineManager.releaseLocal()
                container.settingsStore.update { it.copy(activeEngineId = null) }
            }
            container.modelRepository.deleteWeights(model.id)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

data class ModelsUiState(
    val models: List<LocalModel> = emptyList(),
    val activeModelId: String? = null,
    val freeSpaceBytes: Long = 0,
    val error: String? = null,
)
