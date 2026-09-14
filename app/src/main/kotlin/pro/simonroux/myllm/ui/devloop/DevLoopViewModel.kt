package pro.simonroux.myllm.ui.devloop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.simonroux.myllm.BuildConfig
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.model.AvailableUpdate
import pro.simonroux.myllm.core.model.ChangeRequest
import pro.simonroux.myllm.core.model.ChangeStatus
import pro.simonroux.myllm.update.UpdateProgress

class DevLoopViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        DevLoopUiState(installedVersion = BuildConfig.VERSION_NAME),
    )
    val state: StateFlow<DevLoopUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.changeRequestRepository.observeAll().collect { requests ->
                _state.update { it.copy(requests = requests) }
            }
        }
    }

    fun create(title: String, body: String) {
        viewModelScope.launch {
            container.changeRequestRepository.submit(title, body, "FEATURE")
        }
    }

    fun renderBrief(request: ChangeRequest): String =
        container.changeRequestRepository.renderBrief(request)

    fun markExported(request: ChangeRequest) {
        if (request.status != ChangeStatus.DRAFT) return
        viewModelScope.launch {
            container.changeRequestRepository.setStatus(request.id, ChangeStatus.EXPORTED)
        }
    }

    fun setStatus(request: ChangeRequest, status: ChangeStatus) {
        viewModelScope.launch {
            container.changeRequestRepository.setStatus(request.id, status)
        }
    }

    fun delete(request: ChangeRequest) {
        viewModelScope.launch { container.changeRequestRepository.delete(request.id) }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(updateStatus = "Vérification en cours") }
            val update = container.updater.check()
            _state.update {
                it.copy(
                    availableUpdate = update,
                    updateStatus = update?.let { found -> "Version ${found.versionName} disponible" }
                        ?: "Aucune mise à jour",
                )
            }
        }
    }

    fun downloadAndInstall() {
        val update = _state.value.availableUpdate ?: return
        viewModelScope.launch {
            container.updater.download(update).collect { progress ->
                when (progress) {
                    is UpdateProgress.Downloading -> _state.update {
                        val percent = if (progress.total > 0) {
                            progress.bytes * 100 / progress.total
                        } else {
                            0
                        }
                        it.copy(updateStatus = "Téléchargement $percent %")
                    }

                    is UpdateProgress.Ready -> {
                        _state.update { it.copy(updateStatus = "Installation") }
                        container.updater.install(progress.apk)
                    }

                    is UpdateProgress.Failed -> _state.update {
                        it.copy(updateStatus = "Échec : ${progress.message}")
                    }
                }
            }
        }
    }
}

data class DevLoopUiState(
    val requests: List<ChangeRequest> = emptyList(),
    val installedVersion: String = "",
    val availableUpdate: AvailableUpdate? = null,
    val updateStatus: String? = null,
)
