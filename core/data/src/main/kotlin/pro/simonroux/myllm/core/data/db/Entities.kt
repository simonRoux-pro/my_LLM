package pro.simonroux.myllm.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String?,
    val pinnedEngineId: String?,
    val pinnedModelId: String?,
    val agentEnabled: Boolean,
    val archived: Boolean,
)

/**
 * Messages cascade with their conversation: a deleted conversation must not
 * leave a transcript behind on a device the user considers private.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    /** Serialised list of ToolCall. Empty string when there are none. */
    val toolCallsJson: String,
    val toolCallId: String?,
    val thinking: String?,
    /** Serialised MessageMeta. */
    val metaJson: String?,
)

@Entity(tableName = "skills", indices = [Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parametersJson: String,
    val code: String,
    val language: String,
    val version: Int,
    val enabled: Boolean,
    /** Comma separated SkillPermission names. */
    val permissions: String,
    val createdAt: Long,
    val updatedAt: Long,
    val authoredByModel: Boolean,
    val lastRunAt: Long?,
    val lastRunOk: Boolean?,
    val lastError: String?,
    val timeoutMs: Long,
)

@Entity(
    tableName = "skill_revisions",
    indices = [Index(value = ["skillId", "version"], unique = true)],
)
data class SkillRevisionEntity(
    @PrimaryKey val id: String,
    val skillId: String,
    val version: Int,
    val code: String,
    val parametersJson: String,
    val createdAt: Long,
    val note: String,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** Key/value storage handed to skills, partitioned by skill id. */
@Entity(tableName = "skill_memory", primaryKeys = ["skillId", "key"])
data class SkillMemoryEntity(
    val skillId: String,
    val key: String,
    @ColumnInfo(name = "value") val value: String,
    val updatedAt: Long,
)

@Entity(tableName = "change_requests")
data class ChangeRequestEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val kind: String,
    val createdAt: Long,
    val status: String,
    val issueNumber: Int?,
    val issueUrl: String?,
    val appVersion: String,
    val attachmentsJson: String,
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val family: String,
    val parameterCount: String,
    val quantization: String,
    val sizeBytes: Long,
    val contextLength: Int,
    val downloadUrl: String,
    val sha256: String?,
    val filePath: String?,
    val state: String,
    val downloadedBytes: Long,
    val chatTemplate: String?,
    val supportsTools: Boolean,
    val supportsThinking: Boolean,
    val notes: String,
)
