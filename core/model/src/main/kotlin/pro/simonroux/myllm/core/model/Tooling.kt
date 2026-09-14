package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A tool the model is allowed to call, described the way every function-calling API expects. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object describing the arguments. */
    val parameters: JsonObject,
    val source: ToolSource = ToolSource.BUILT_IN,
    /** Tools the model must never call on its own initiative. */
    val requiresConfirmation: Boolean = false,
)

@Serializable
enum class ToolSource {
    /** Compiled into the app. */
    BUILT_IN,

    /** A user or model authored script, loaded at runtime. */
    SKILL,
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonElement,
)

@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val ok: Boolean,
    /** Rendered result handed back to the model. Kept as text because that is what models consume. */
    val content: String,
    val durationMs: Long = 0,
)
