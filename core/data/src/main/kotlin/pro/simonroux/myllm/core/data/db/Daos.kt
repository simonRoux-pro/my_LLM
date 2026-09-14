package pro.simonroux.myllm.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun forConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Used when a turn is retried: everything after the given message goes. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt > :timestamp")
    suspend fun deleteAfter(conversationId: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun count(conversationId: String): Int

    @Query(
        "SELECT * FROM messages WHERE content LIKE '%' || :term || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(term: String, limit: Int = 50): List<MessageEntity>
}

@Dao
interface SkillDao {

    @Query("SELECT * FROM skills ORDER BY name ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name ASC")
    suspend fun all(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY name ASC")
    suspend fun enabled(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun byId(id: String): SkillEntity?

    @Upsert
    suspend fun upsert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("UPDATE skills SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query(
        "UPDATE skills SET lastRunAt = :timestamp, lastRunOk = :ok, lastError = :error WHERE id = :id",
    )
    suspend fun recordRun(id: String, timestamp: Long, ok: Boolean, error: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(revision: SkillRevisionEntity)

    @Query("SELECT * FROM skill_revisions WHERE skillId = :skillId ORDER BY version DESC")
    suspend fun revisions(skillId: String): List<SkillRevisionEntity>

    @Query("SELECT * FROM skill_revisions WHERE skillId = :skillId AND version = :version")
    suspend fun revision(skillId: String, version: Int): SkillRevisionEntity?

    /**
     * Saving a skill and its revision has to be one unit: a stored skill whose
     * previous body was never recorded cannot be rolled back.
     */
    @Transaction
    suspend fun upsertWithRevision(skill: SkillEntity, revision: SkillRevisionEntity) {
        upsert(skill)
        insertRevision(revision)
    }
}

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE `key` = :key")
    suspend fun byKey(key: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}

@Dao
interface SkillMemoryDao {

    @Query("SELECT `value` FROM skill_memory WHERE skillId = :skillId AND `key` = :key")
    suspend fun read(skillId: String, key: String): String?

    @Upsert
    suspend fun write(entry: SkillMemoryEntity)

    @Query("DELETE FROM skill_memory WHERE skillId = :skillId AND `key` = :key")
    suspend fun remove(skillId: String, key: String)

    @Query("SELECT `key` FROM skill_memory WHERE skillId = :skillId ORDER BY `key` ASC")
    suspend fun keys(skillId: String): List<String>

    @Query("DELETE FROM skill_memory WHERE skillId = :skillId")
    suspend fun clear(skillId: String)
}

@Dao
interface ChangeRequestDao {

    @Query("SELECT * FROM change_requests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChangeRequestEntity>>

    @Query("SELECT * FROM change_requests WHERE id = :id")
    suspend fun byId(id: String): ChangeRequestEntity?

    @Upsert
    suspend fun upsert(request: ChangeRequestEntity)

    @Query("UPDATE change_requests SET status = :status, issueNumber = :issueNumber, issueUrl = :issueUrl WHERE id = :id")
    suspend fun markPushed(id: String, status: String, issueNumber: Int?, issueUrl: String?)

    @Query("DELETE FROM change_requests WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ModelDao {

    @Query("SELECT * FROM models ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE state = 'READY' ORDER BY displayName ASC")
    fun observeReady(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun byId(id: String): ModelEntity?

    @Query("SELECT * FROM models")
    suspend fun all(): List<ModelEntity>

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Upsert
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("UPDATE models SET state = :state, downloadedBytes = :downloadedBytes WHERE id = :id")
    suspend fun updateProgress(id: String, state: String, downloadedBytes: Long)

    @Query("UPDATE models SET state = :state, filePath = :filePath WHERE id = :id")
    suspend fun markReady(id: String, state: String, filePath: String?)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}
