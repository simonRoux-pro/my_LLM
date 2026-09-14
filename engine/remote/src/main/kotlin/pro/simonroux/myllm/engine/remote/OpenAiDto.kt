package pro.simonroux.myllm.engine.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the /chat/completions shape.
 *
 * This one shape is understood by OpenRouter, Groq, Together, Cerebras, DeepSeek,
 * Mistral, Google's OpenAI-compatible endpoint, Ollama and llama.cpp's own
 * server, which is why the app has a single remote engine rather than one per
 * provider. Field names stay snake_case to match the protocol.
 */
@Serializable
internal data class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val tools: List<ToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

@Serializable
internal data class StreamOptionsDto(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
internal data class MessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
internal data class ToolDto(
    val type: String = "function",
    val function: FunctionDto,
)

@Serializable
internal data class FunctionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class ToolCallDto(
    val id: String? = null,
    /** Present only in streaming deltas, where it identifies which call a fragment belongs to. */
    val index: Int? = null,
    val type: String? = null,
    val function: ToolCallFunctionDto? = null,
)

@Serializable
internal data class ToolCallFunctionDto(
    val name: String? = null,
    /** A JSON string, streamed in fragments that have to be concatenated before parsing. */
    val arguments: String? = null,
)

@Serializable
internal data class StreamChunkDto(
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: ErrorDto? = null,
)

@Serializable
internal data class StreamChoiceDto(
    val index: Int = 0,
    val delta: DeltaDto = DeltaDto(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    /** DeepSeek and Qwen name the reasoning channel differently; both are accepted. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
internal data class ErrorDto(
    val message: String = "",
    val type: String? = null,
    val code: String? = null,
)

@Serializable
internal data class ErrorEnvelopeDto(
    val error: ErrorDto? = null,
)
