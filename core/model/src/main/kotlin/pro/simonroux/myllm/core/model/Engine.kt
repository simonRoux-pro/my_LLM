package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable

/** Where inference runs. */
@Serializable
enum class EngineKind {
    /** llama.cpp on this device. Works with no network. */
    LOCAL,

    /** An OpenAI-compatible HTTP endpoint. */
    REMOTE,
}

/**
 * A configured, selectable inference target.
 *
 * A remote engine is a saved endpoint plus credentials; a local engine is a
 * loaded GGUF plus its runtime config.
 */
@Serializable
data class EngineDescriptor(
    val id: String,
    val label: String,
    val kind: EngineKind,
    val enabled: Boolean = true,
    /** Lower runs first when the router falls through a list. */
    val priority: Int = 100,
    val local: LocalEngineConfig? = null,
    val remote: RemoteEngineConfig? = null,
)

@Serializable
data class LocalEngineConfig(
    val modelId: String,
    val runtime: RuntimeConfig = RuntimeConfig(),
)

@Serializable
data class RemoteEngineConfig(
    /** Base URL without the trailing path, e.g. https://openrouter.ai/api/v1 */
    val baseUrl: String,
    val modelId: String,
    /** Alias into the secret store. The key itself never lives in this object. */
    val apiKeyAlias: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val timeoutSeconds: Int = 120,
)

/**
 * How the router picks an engine for a request.
 */
@Serializable
enum class RoutingPolicy {
    /** Never leaves the device, whatever the cost in quality. */
    LOCAL_ONLY,

    /** Local first; fall through to remote only if local is unavailable or fails. */
    LOCAL_FIRST,

    /** Remote first for quality; fall back to local when offline or on error. */
    REMOTE_FIRST,

    /** Always remote. Fails when there is no network. */
    REMOTE_ONLY,
}
