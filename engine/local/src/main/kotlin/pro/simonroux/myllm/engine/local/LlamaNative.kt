package pro.simonroux.myllm.engine.local

/**
 * Raw JNI surface over llama.cpp. One function per native entry point, no logic.
 *
 * Every pointer here is an opaque handle owned by the native side. Passing a
 * stale handle is undefined behaviour, so [LlamaCppEngine] is the only thing
 * allowed to hold them and it serialises all access onto a single thread.
 */
internal object LlamaNative {

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /**
     * Loads libmyllm_llama.so. Returns false on a device where the ABI does not
     * match, rather than throwing, so the app can still run remote-only.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("myllm_llama")
            backendInit()
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "libmyllm_llama.so introuvable"
            false
        }
    }

    external fun backendInit()
    external fun backendFree()
    external fun systemInfo(): String

    external fun loadModel(path: String, nGpuLayers: Int, loadMode: Int): Long
    external fun freeModel(modelPtr: Long)
    external fun modelDescription(modelPtr: Long): String
    external fun modelContextLength(modelPtr: Long): Int
    external fun modelParamCount(modelPtr: Long): Long
    external fun modelChatTemplate(modelPtr: Long): String?

    external fun newContext(
        modelPtr: Long,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        flashAttn: Int,
    ): Long

    external fun freeContext(ctxPtr: Long)
    external fun contextLength(ctxPtr: Long): Int
    external fun clearMemory(ctxPtr: Long)
    external fun trimMemory(ctxPtr: Long, fromPos: Int): Boolean
    external fun setAbort(ctxPtr: Long, abort: Boolean)

    external fun tokenize(
        modelPtr: Long,
        text: String,
        addSpecial: Boolean,
        parseSpecial: Boolean,
    ): IntArray

    external fun tokenToBytes(modelPtr: Long, token: Int): ByteArray
    external fun isEndOfGeneration(modelPtr: Long, token: Int): Boolean
    external fun vocabSize(modelPtr: Long): Int

    external fun decode(ctxPtr: Long, tokens: IntArray, nPast: Int, wantLogits: Boolean): Int

    external fun newSampler(
        modelPtr: Long,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
    ): Long

    external fun freeSampler(samplerPtr: Long)
    external fun resetSampler(samplerPtr: Long)
    external fun sample(ctxPtr: Long, samplerPtr: Long): Int

    external fun applyChatTemplate(
        template: String,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** Mirrors llama_load_mode. */
    object LoadMode {
        const val NONE = 0
        const val MMAP = 1
        const val MMAP_MLOCK = 3
    }

    /** Mirrors llama_flash_attn_type. */
    object FlashAttention {
        const val AUTO = -1
        const val DISABLED = 0
        const val ENABLED = 1
    }
}
