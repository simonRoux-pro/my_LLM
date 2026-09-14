package pro.simonroux.myllm.core.data.repo

import pro.simonroux.myllm.core.model.LocalModel

/**
 * Starting suggestions, sized for a phone with 12 GB of RAM and four fast cores.
 *
 * This is a convenience, not a whitelist: any GGUF can be added by URL or
 * imported from storage, which is the path to use when a better model appears
 * next month. Sizes are approximate and the real one is read from the response
 * headers at download time.
 *
 * Picking a quantisation: Q4_K_M is the point where quality loss stops being
 * noticeable in conversation while the file still fits comfortably in RAM. Q8
 * and F16 are not listed because a phone has no memory bandwidth to spare for
 * them, and Q2 and Q3 degrade instruction following badly enough that tool
 * calling stops working.
 */
object ModelCatalog {

    /** Roughly how much RAM a model needs beyond its file size, for the KV cache. */
    private const val KV_OVERHEAD_FACTOR = 1.25

    val suggestions: List<LocalModel> = listOf(
        LocalModel(
            id = "qwen2.5-3b-instruct-q4km",
            displayName = "Qwen2.5 3B Instruct",
            family = "Qwen2.5",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            sizeBytes = 1_930_000_000,
            contextLength = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            supportsTools = true,
            notes = "Le meilleur compromis pour un usage quotidien hors ligne. " +
                "Rapide, suit les instructions, sait appeler des outils.",
        ),
        LocalModel(
            id = "qwen2.5-7b-instruct-q4km",
            displayName = "Qwen2.5 7B Instruct",
            family = "Qwen2.5",
            parameterCount = "7B",
            quantization = "Q4_K_M",
            sizeBytes = 4_680_000_000,
            contextLength = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            supportsTools = true,
            notes = "Nettement plus solide en raisonnement et en code. " +
                "Compte environ 6 Go de RAM en usage et une génération plus lente.",
        ),
        LocalModel(
            id = "qwen2.5-coder-7b-q4km",
            displayName = "Qwen2.5 Coder 7B",
            family = "Qwen2.5",
            parameterCount = "7B",
            quantization = "Q4_K_M",
            sizeBytes = 4_680_000_000,
            contextLength = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            supportsTools = true,
            notes = "Spécialisé code. C'est celui à charger pour écrire des skills hors ligne.",
        ),
        LocalModel(
            id = "llama-3.2-3b-instruct-q4km",
            displayName = "Llama 3.2 3B Instruct",
            family = "Llama 3.2",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            sizeBytes = 2_020_000_000,
            contextLength = 131072,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            supportsTools = true,
            notes = "Très bon en français, contexte long. Alternative à Qwen si le style plaît mieux.",
        ),
        LocalModel(
            id = "llama-3.2-1b-instruct-q4km",
            displayName = "Llama 3.2 1B Instruct",
            family = "Llama 3.2",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            sizeBytes = 810_000_000,
            contextLength = 131072,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            supportsTools = false,
            notes = "Très rapide et léger. Pour des réponses courtes et du dépannage, " +
                "pas pour du raisonnement ni des outils.",
        ),
    )

    /** Rough RAM requirement, to warn before a download that will not fit. */
    fun estimatedRamBytes(model: LocalModel): Long =
        (model.sizeBytes * KV_OVERHEAD_FACTOR).toLong()

    fun byId(id: String): LocalModel? = suggestions.firstOrNull { it.id == id }
}
