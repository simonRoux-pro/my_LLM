package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable

/**
 * A GGUF weight file, either already on the device or offered for download.
 *
 * [id] is stable across installs so a catalog entry and its downloaded copy
 * refer to the same thing.
 */
@Serializable
data class LocalModel(
    val id: String,
    val displayName: String,
    val family: String,
    val parameterCount: String,
    val quantization: String,
    val sizeBytes: Long,
    val contextLength: Int,
    /** Direct URL to the .gguf file. Empty for models imported from local storage. */
    val downloadUrl: String = "",
    val sha256: String? = null,
    /** Absolute path once downloaded. Null while the model is only a catalog entry. */
    val filePath: String? = null,
    val state: ModelState = ModelState.AVAILABLE,
    val downloadedBytes: Long = 0,
    /** Chat template override. When null the template embedded in the GGUF is used. */
    val chatTemplate: String? = null,
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    val notes: String = "",
) {
    val isReady: Boolean get() = state == ModelState.READY && filePath != null

    val progress: Float
        get() = if (sizeBytes <= 0) 0f else (downloadedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
}

@Serializable
enum class ModelState {
    /** Known from the catalog, not on the device. */
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY,
    FAILED,
}

/**
 * How much of the model can be pushed to the GPU and how the CPU side is sized.
 * Defaults are tuned for a device with 8 GB or more of RAM.
 */
@Serializable
data class RuntimeConfig(
    val contextSize: Int = 4096,
    /** Prompt batch size. Larger is faster to ingest but uses more memory. */
    val batchSize: Int = 512,
    /** 0 keeps everything on the CPU. Raise only once GPU offload is proven stable. */
    val gpuLayers: Int = 0,
    /** -1 lets the engine pick, which means the number of performance cores. */
    val threads: Int = -1,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val flashAttention: Boolean = true,
)
