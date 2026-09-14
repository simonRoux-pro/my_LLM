package pro.simonroux.myllm.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.simonroux.myllm.core.data.repo.ModelRepository
import pro.simonroux.myllm.core.data.store.SecretStore
import pro.simonroux.myllm.core.data.store.SettingsStore
import pro.simonroux.myllm.core.model.AppError
import pro.simonroux.myllm.core.model.EngineDescriptor
import pro.simonroux.myllm.core.model.EngineKind
import pro.simonroux.myllm.core.model.Outcome
import pro.simonroux.myllm.core.model.RoutingPolicy
import pro.simonroux.myllm.engine.api.LlmEngine
import pro.simonroux.myllm.engine.local.LlamaCppEngine
import pro.simonroux.myllm.engine.remote.OpenAiCompatibleEngine

/**
 * Decides which engine answers, and keeps at most one model in memory.
 *
 * Two constraints shape this. A loaded GGUF occupies gigabytes, so exactly one
 * local engine may be prepared at a time and switching means releasing the
 * previous one first. And the offline switch has to be honoured at the moment of
 * the call, not at configuration time, because the user flips it precisely when
 * they do not want traffic leaving.
 */
class EngineManager(
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val models: ModelRepository,
) {

    private val mutex = Mutex()

    private var activeLocal: LlamaCppEngine? = null
    private var activeLocalModelId: String? = null

    private val _active = MutableStateFlow<LlmEngine?>(null)
    val active: StateFlow<LlmEngine?> = _active.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * Returns the engines the router may use, in the order it should try them.
     *
     * A local engine is only listed when its weights are actually on disk, so a
     * catalogue entry the user never downloaded never becomes a dead end at
     * generation time.
     */
    suspend fun candidates(): List<EngineDescriptor> {
        val settings = settingsStore.current()
        val configured = settingsStore.currentEngines().filter { it.enabled }

        val localReady = models.all().filter { it.isReady }.map { it.id }.toSet()
        val usable = configured.filter { descriptor ->
            when (descriptor.kind) {
                EngineKind.LOCAL -> descriptor.local?.modelId in localReady
                EngineKind.REMOTE -> !settings.offlineOnly
            }
        }

        val (local, remote) = usable.partition { it.kind == EngineKind.LOCAL }

        return when (settings.routingPolicy) {
            RoutingPolicy.LOCAL_ONLY -> local
            RoutingPolicy.LOCAL_FIRST -> local.sortedBy { it.priority } + remote.sortedBy { it.priority }
            RoutingPolicy.REMOTE_FIRST -> remote.sortedBy { it.priority } + local.sortedBy { it.priority }
            RoutingPolicy.REMOTE_ONLY -> remote
        }
    }

    /**
     * Prepares and returns the engine to use for the next request.
     *
     * [preferredId] pins a conversation to one engine. When it is not usable the
     * router falls through rather than failing, because a pinned model that was
     * deleted should degrade to whatever works, not to an error.
     */
    suspend fun acquire(preferredId: String? = null): Outcome<LlmEngine> = mutex.withLock {
        val ordered = candidates()
        if (ordered.isEmpty()) {
            val settings = settingsStore.current()
            return Outcome.Err(
                when {
                    settings.offlineOnly -> AppError.ModelNotLoaded(
                        "Mode hors ligne et aucun modèle local prêt. " +
                            "Télécharge un modèle dans l'onglet Modèles.",
                    )
                    else -> AppError.ModelNotLoaded("Aucun moteur configuré.")
                },
            )
        }

        val queue = buildList {
            preferredId?.let { id -> ordered.firstOrNull { it.id == id }?.let(::add) }
            addAll(ordered.filter { it.id != preferredId })
        }

        var lastError: AppError? = null
        for (descriptor in queue) {
            when (val attempt = instantiate(descriptor)) {
                is Outcome.Ok -> {
                    _active.value = attempt.value
                    _status.value = null
                    return Outcome.Ok(attempt.value)
                }
                is Outcome.Err -> {
                    lastError = attempt.error
                    _status.value = "${descriptor.label} indisponible : ${attempt.error.message}"
                }
            }
        }

        Outcome.Err(lastError ?: AppError.Unexpected("Aucun moteur n'a pu démarrer"))
    }

    private suspend fun instantiate(descriptor: EngineDescriptor): Outcome<LlmEngine> =
        when (descriptor.kind) {
            EngineKind.LOCAL -> prepareLocal(descriptor)
            EngineKind.REMOTE -> prepareRemote(descriptor)
        }

    private suspend fun prepareLocal(descriptor: EngineDescriptor): Outcome<LlmEngine> {
        val config = descriptor.local
            ?: return Outcome.Err(AppError.Unexpected("Configuration locale manquante"))

        activeLocal?.let { existing ->
            if (activeLocalModelId == config.modelId) return Outcome.Ok(existing)

            // Releasing before loading is not an optimisation, it is the only way
            // a 7B model can replace another one on a phone without being killed
            // by the low memory reaper halfway through.
            _status.value = "Libération du modèle précédent"
            existing.release()
            activeLocal = null
            activeLocalModelId = null
        }

        val model = models.byId(config.modelId)
            ?: return Outcome.Err(AppError.ModelNotLoaded("Modèle ${config.modelId} inconnu"))
        val path = model.filePath
            ?: return Outcome.Err(AppError.ModelNotLoaded("${model.displayName} n'est pas téléchargé"))

        val runtime = settingsStore.current().runtime.let { global ->
            // The per-engine runtime wins where it was set, so a small model can
            // keep a large context without forcing it on every other one.
            config.runtime.copy(
                contextSize = config.runtime.contextSize.takeIf { it > 0 } ?: global.contextSize,
            )
        }

        val engine = LlamaCppEngine(
            id = descriptor.id,
            label = descriptor.label,
            modelPath = path,
            runtime = runtime,
            chatTemplateOverride = model.chatTemplate,
        )

        _status.value = "Chargement de ${model.displayName}"
        return when (val prepared = engine.prepare()) {
            is Outcome.Ok -> {
                activeLocal = engine
                activeLocalModelId = config.modelId
                Outcome.Ok(engine)
            }
            is Outcome.Err -> {
                engine.release()
                prepared
            }
        }
    }

    private suspend fun prepareRemote(descriptor: EngineDescriptor): Outcome<LlmEngine> {
        val config = descriptor.remote
            ?: return Outcome.Err(AppError.Unexpected("Configuration distante manquante"))

        val engine = OpenAiCompatibleEngine(
            id = descriptor.id,
            label = descriptor.label,
            config = config,
            apiKeyProvider = { config.apiKeyAlias?.let { secretStore.get(it) } },
            allowNetwork = { !settingsStore.current().offlineOnly },
        )

        return when (val prepared = engine.prepare()) {
            is Outcome.Ok -> Outcome.Ok(engine)
            is Outcome.Err -> prepared
        }
    }

    /** Frees the loaded model. Called when the app goes to the background for a while. */
    suspend fun releaseLocal() = mutex.withLock {
        activeLocal?.release()
        activeLocal = null
        activeLocalModelId = null
        if (_active.value?.kind == EngineKind.LOCAL) _active.value = null
    }
}
