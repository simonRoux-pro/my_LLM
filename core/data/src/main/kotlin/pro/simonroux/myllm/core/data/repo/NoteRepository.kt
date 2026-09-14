package pro.simonroux.myllm.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.simonroux.myllm.agent.tools.NoteStore
import pro.simonroux.myllm.core.data.db.NoteDao
import pro.simonroux.myllm.core.data.db.NoteEntity

/**
 * What the assistant has been told to remember about its user.
 *
 * Deliberately separate from conversation history: the user should be able to
 * read and prune this list without scrolling through months of chat, because it
 * is the part that shapes every future answer.
 */
class NoteRepository(private val dao: NoteDao) : NoteStore {

    fun observeAll(): Flow<Map<String, String>> =
        dao.observeAll().map { list -> list.associate { it.key to it.value } }

    override suspend fun get(key: String): String? = dao.byKey(key)?.value

    override suspend fun put(key: String, value: String) =
        dao.upsert(NoteEntity(key, value, System.currentTimeMillis()))

    override suspend fun remove(key: String): Boolean = dao.delete(key) > 0

    override suspend fun all(): Map<String, String> = dao.all().associate { it.key to it.value }

    suspend fun clear() = dao.deleteAll()
}
