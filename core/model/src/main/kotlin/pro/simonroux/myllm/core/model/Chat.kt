package pro.simonroux.myllm.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who produced a message. Mirrors the roles every chat-tuned model understands. */
@Serializable
enum class ChatRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,

    /** Output of a tool, fed back into the model. */
    @SerialName("tool") TOOL,
}

/**
 * One turn in a conversation.
 *
 * [toolCalls] is set on assistant turns that ask for tool execution.
 * [toolCallId] is set on TOOL turns and points back to the call being answered.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** Model-internal reasoning, kept separate so it can be hidden from the prompt. */
    val thinking: String? = null,
    val meta: MessageMeta? = null,
)

/** Provenance and cost of a generated message. Useful for debugging and for routing decisions. */
@Serializable
data class MessageMeta(
    val engineId: String? = null,
    val modelId: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Tokens per second for the decode phase. */
    val tokensPerSecond: Double = 0.0,
    val latencyMs: Long = 0,
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val offline: Boolean = true,
)

@Serializable
enum class FinishReason { STOP, LENGTH, TOOL_CALLS, CANCELLED, ERROR, UNKNOWN }

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Overrides the global system prompt when set. */
    val systemPrompt: String? = null,
    /** Pins this conversation to a specific engine instead of using the router. */
    val pinnedEngineId: String? = null,
    val pinnedModelId: String? = null,
    val agentEnabled: Boolean = true,
    val archived: Boolean = false,
)
