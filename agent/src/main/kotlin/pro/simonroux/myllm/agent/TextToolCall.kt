package pro.simonroux.myllm.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.simonroux.myllm.core.model.ToolCall
import pro.simonroux.myllm.core.model.ToolSpec

/**
 * Tool calling for models that have no structured channel for it.
 *
 * A hosted endpoint returns tool calls as JSON fields. A GGUF model decoded
 * through llama.cpp returns text and nothing else, so the calls have to be
 * asked for in the prompt and read back out of the reply.
 *
 * The `<tool_call>` tag is the convention Qwen 2.5 and Qwen 3 are trained on and
 * the one most other open instruct models imitate, so it is the one with the
 * best chance of being produced without fine-tuning.
 */
object TextToolCall {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TAGGED = Regex("""<tool_call>\s*(\{.*?})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
    private val FENCED = Regex("""```(?:json|tool_call)?\s*(\{[^`]*?"name"\s*:[^`]*?})\s*```""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Instructions appended to the system prompt so the model knows what it can
     * call and in which syntax.
     */
    fun buildPromptSection(specs: List<ToolSpec>): String {
        if (specs.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("# Outils disponibles")
            appendLine()
            appendLine("Tu peux appeler un outil en écrivant exactement ce bloc, seul sur ses lignes :")
            appendLine()
            appendLine("<tool_call>")
            appendLine("""{"name": "nom_de_l_outil", "arguments": {"cle": "valeur"}}""")
            appendLine("</tool_call>")
            appendLine()
            appendLine("Règles :")
            appendLine("- Un seul bloc par message, puis arrête-toi et attends le résultat.")
            appendLine("- N'invente jamais le résultat d'un outil.")
            appendLine("- Si aucun outil n'est utile, réponds normalement, sans bloc.")
            appendLine()
            appendLine("Outils :")
            specs.forEach { spec ->
                appendLine()
                appendLine("## ${spec.name}")
                appendLine(spec.description)
                appendLine("Paramètres (JSON Schema) : ${spec.parameters}")
            }
        }
    }

    /**
     * Extracts calls from a completed assistant message.
     *
     * Returns the calls plus the text with the call blocks removed, so the user
     * never sees the raw protocol in the transcript.
     */
    fun parse(text: String): ParsedToolCalls {
        val calls = mutableListOf<ToolCall>()
        var cleaned = text

        fun consume(regex: Regex) {
            regex.findAll(cleaned).forEach { match ->
                decode(match.groupValues[1], calls.size)?.let(calls::add)
            }
            if (calls.isNotEmpty()) cleaned = regex.replace(cleaned, "")
        }

        consume(TAGGED)
        if (calls.isEmpty()) consume(FENCED)

        return ParsedToolCalls(calls = calls, text = cleaned.trim())
    }

    /**
     * True when the text so far contains an opening tag whose closing tag has
     * not arrived. The streaming UI uses this to stop showing raw protocol
     * mid-flight.
     */
    fun hasOpenCall(text: String): Boolean {
        val open = text.lastIndexOf("<tool_call>")
        if (open < 0) return false
        return text.indexOf("</tool_call>", open) < 0
    }

    private fun decode(payload: String, index: Int): ToolCall? = runCatching {
        val obj: JsonObject = json.parseToJsonElement(payload).jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val arguments = obj["arguments"] ?: obj["parameters"] ?: JsonObject(emptyMap())
        ToolCall(id = "local_$index", name = name, arguments = arguments)
    }.getOrNull()
}

data class ParsedToolCalls(
    val calls: List<ToolCall>,
    val text: String,
)
