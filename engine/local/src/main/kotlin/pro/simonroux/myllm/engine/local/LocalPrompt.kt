package pro.simonroux.myllm.engine.local

import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.ChatRole
import pro.simonroux.myllm.engine.api.ChatTemplate

/**
 * Turns a message list into the exact string the loaded model was trained on.
 *
 * Preference order: an explicit override, then the template embedded in the
 * GGUF, then ChatML. Getting this wrong is the most common cause of a local
 * model producing confident nonsense, so [usingNativeTemplate] is surfaced in
 * the UI rather than being silently wrong.
 */
internal class LocalPrompt(
    private val modelPtr: Long,
    templateOverride: String?,
) {

    private val template: String =
        templateOverride ?: LlamaNative.modelChatTemplate(modelPtr).orEmpty()

    var usingNativeTemplate: Boolean = false
        private set

    fun render(messages: List<ChatMessage>, systemPrompt: String?): String {
        val roles = ArrayList<String>(messages.size + 1)
        val contents = ArrayList<String>(messages.size + 1)

        if (!systemPrompt.isNullOrBlank()) {
            roles += "system"
            contents += systemPrompt
        }
        for (message in messages) {
            roles += message.role.templateName()
            contents += message.content
        }

        val rendered = LlamaNative.applyChatTemplate(
            template = template,
            roles = roles.toTypedArray(),
            contents = contents.toTypedArray(),
            addAssistant = true,
        )

        usingNativeTemplate = rendered != null
        return rendered
            ?: ChatTemplate.ChatMl.render(messages, systemPrompt, addGenerationPrompt = true)
    }

    private fun ChatRole.templateName(): String = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        ChatRole.TOOL -> "tool"
    }
}
