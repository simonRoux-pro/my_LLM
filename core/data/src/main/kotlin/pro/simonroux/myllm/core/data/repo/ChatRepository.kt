package pro.simonroux.myllm.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.simonroux.myllm.core.data.db.ConversationDao
import pro.simonroux.myllm.core.data.db.MessageDao
import pro.simonroux.myllm.core.data.db.Mappers.toDomain
import pro.simonroux.myllm.core.data.db.Mappers.toEntity
import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.Conversation
import java.util.UUID

/** Conversations and their transcripts. */
class ChatRepository(
    private val conversations: ConversationDao,
    private val messages: MessageDao,
) {

    fun observeConversations(): Flow<List<Conversation>> =
        conversations.observeActive().map { list -> list.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messages.observe(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun conversation(id: String): Conversation? = conversations.byId(id)?.toDomain()

    suspend fun history(conversationId: String): List<ChatMessage> =
        messages.forConversation(conversationId).map { it.toDomain() }

    suspend fun createConversation(title: String = "Nouvelle conversation"): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        conversations.upsert(conversation.toEntity())
        return conversation
    }

    suspend fun save(message: ChatMessage) {
        messages.insert(message.toEntity())
        conversations.touch(message.conversationId, message.createdAt)
    }

    suspend fun saveAll(newMessages: List<ChatMessage>) {
        if (newMessages.isEmpty()) return
        messages.insertAll(newMessages.map { it.toEntity() })
        conversations.touch(newMessages.first().conversationId, newMessages.maxOf { it.createdAt })
    }

    suspend fun update(conversation: Conversation) =
        conversations.upsert(conversation.copy(updatedAt = System.currentTimeMillis()).toEntity())

    suspend fun rename(id: String, title: String) = conversations.rename(id, title)

    suspend fun archive(id: String) = conversations.setArchived(id, true)

    suspend fun delete(id: String) = conversations.delete(id)

    suspend fun deleteMessage(id: String) = messages.deleteById(id)

    /** Drops everything after a message, so a turn can be re-run from that point. */
    suspend fun rewindTo(message: ChatMessage) =
        messages.deleteAfter(message.conversationId, message.createdAt)

    suspend fun search(term: String): List<ChatMessage> =
        messages.search(term).map { it.toDomain() }

    /** Erases every conversation. Offered in settings as a single privacy action. */
    suspend fun deleteEverything() = conversations.deleteAll()

    /**
     * Derives a title from the first user message.
     *
     * Asking the model for one would be nicer, but that costs a generation on a
     * phone and the first sentence is almost always what the user would pick.
     */
    fun deriveTitle(firstMessage: String): String {
        val cleaned = firstMessage.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return "Nouvelle conversation"
        val cut = cleaned.take(48)
        return if (cleaned.length > 48) cut.substringBeforeLast(' ', cut) + "..." else cut
    }
}
