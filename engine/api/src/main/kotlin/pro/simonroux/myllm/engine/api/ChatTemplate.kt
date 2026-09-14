package pro.simonroux.myllm.engine.api

import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.ChatRole

/**
 * Renders a message list into the flat prompt a base model expects.
 *
 * Remote engines never need this because the server applies the template. Local
 * engines use the template embedded in the GGUF when there is one, and fall back
 * to [ChatMl] otherwise, which most instruction-tuned models tolerate.
 */
fun interface ChatTemplate {
    fun render(messages: List<ChatMessage>, systemPrompt: String?, addGenerationPrompt: Boolean): String

    companion object {
        val ChatMl = ChatTemplate { messages, systemPrompt, addGenerationPrompt ->
            buildString {
                if (!systemPrompt.isNullOrBlank()) {
                    append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
                }
                for (m in messages) {
                    val role = when (m.role) {
                        ChatRole.SYSTEM -> "system"
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.TOOL -> "tool"
                    }
                    append("<|im_start|>").append(role).append('\n')
                    append(m.content)
                    append("<|im_end|>\n")
                }
                if (addGenerationPrompt) append("<|im_start|>assistant\n")
            }
        }

        val Llama3 = ChatTemplate { messages, systemPrompt, addGenerationPrompt ->
            buildString {
                append("<|begin_of_text|>")
                if (!systemPrompt.isNullOrBlank()) {
                    append("<|start_header_id|>system<|end_header_id|>\n\n")
                    append(systemPrompt).append("<|eot_id|>")
                }
                for (m in messages) {
                    val role = when (m.role) {
                        ChatRole.SYSTEM -> "system"
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.TOOL -> "ipython"
                    }
                    append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                    append(m.content).append("<|eot_id|>")
                }
                if (addGenerationPrompt) append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
        }
    }
}
