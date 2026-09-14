package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable

/**
 * Sampling and decoding parameters.
 *
 * Defaults are deliberately conservative: they are the ones that behave
 * reasonably across both a 1B local model and a large hosted one.
 */
@Serializable
data class GenerationParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val maxTokens: Int = 2048,
    val seed: Int = -1,
    val stopSequences: List<String> = emptyList(),
) {
    companion object {
        /** Near-greedy, for tool calling and structured output where creativity hurts. */
        val PRECISE = GenerationParams(temperature = 0.2f, topP = 0.9f, topK = 20, minP = 0.0f)
    }
}

/** A chunk emitted while a model generates. */
sealed interface GenerationEvent {
    /** The prompt has been ingested; decoding starts now. */
    data class PromptProcessed(val tokens: Int, val elapsedMs: Long) : GenerationEvent

    data class Token(val text: String) : GenerationEvent

    /** Reasoning text, when the model exposes it separately. */
    data class Thinking(val text: String) : GenerationEvent

    data class ToolCallRequested(val call: ToolCall) : GenerationEvent

    data class Completed(val meta: MessageMeta) : GenerationEvent

    data class Failed(val error: String, val recoverable: Boolean) : GenerationEvent
}
