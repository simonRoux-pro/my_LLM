package pro.simonroux.myllm.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pro.simonroux.myllm.core.model.ChangeAttachment
import pro.simonroux.myllm.core.model.ChangeKind
import pro.simonroux.myllm.core.model.ChangeRequest
import pro.simonroux.myllm.core.model.ChangeStatus
import pro.simonroux.myllm.core.model.ChatMessage
import pro.simonroux.myllm.core.model.ChatRole
import pro.simonroux.myllm.core.model.Conversation
import pro.simonroux.myllm.core.model.LocalModel
import pro.simonroux.myllm.core.model.MessageMeta
import pro.simonroux.myllm.core.model.ModelState
import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillLanguage
import pro.simonroux.myllm.core.model.SkillPermission
import pro.simonroux.myllm.core.model.SkillRevision
import pro.simonroux.myllm.core.model.ToolCall

/**
 * Entity to domain conversion.
 *
 * Kept as plain functions rather than Room TypeConverters: converters hide JSON
 * failures inside generated code, and a corrupt row should degrade to a sane
 * default rather than crash a query.
 */
internal object Mappers {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val toolCallListSerializer = ListSerializer(ToolCall.serializer())
    private val attachmentListSerializer = ListSerializer(ChangeAttachment.serializer())

    // --- conversations ----------------------------------------------------

    fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        systemPrompt = systemPrompt,
        pinnedEngineId = pinnedEngineId,
        pinnedModelId = pinnedModelId,
        agentEnabled = agentEnabled,
        archived = archived,
    )

    fun Conversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        systemPrompt = systemPrompt,
        pinnedEngineId = pinnedEngineId,
        pinnedModelId = pinnedModelId,
        agentEnabled = agentEnabled,
        archived = archived,
    )

    // --- messages ---------------------------------------------------------

    fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.USER),
        content = content,
        createdAt = createdAt,
        toolCalls = toolCallsJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(toolCallListSerializer, it) }.getOrNull() }
            .orEmpty(),
        toolCallId = toolCallId,
        thinking = thinking,
        meta = metaJson?.let {
            runCatching { json.decodeFromString(MessageMeta.serializer(), it) }.getOrNull()
        },
    )

    fun ChatMessage.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        createdAt = createdAt,
        toolCallsJson = if (toolCalls.isEmpty()) "" else json.encodeToString(toolCallListSerializer, toolCalls),
        toolCallId = toolCallId,
        thinking = thinking,
        metaJson = meta?.let { json.encodeToString(MessageMeta.serializer(), it) },
    )

    // --- skills -----------------------------------------------------------

    fun SkillEntity.toDomain() = Skill(
        id = id,
        name = name,
        description = description,
        parameters = parseObject(parametersJson),
        code = code,
        language = runCatching { SkillLanguage.valueOf(language) }
            .getOrDefault(SkillLanguage.JAVASCRIPT),
        version = version,
        enabled = enabled,
        permissions = permissions.split(',')
            .mapNotNull { name -> SkillPermission.entries.firstOrNull { it.name == name.trim() } }
            .toSet(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        authoredByModel = authoredByModel,
        lastRunAt = lastRunAt,
        lastRunOk = lastRunOk,
        lastError = lastError,
        timeoutMs = timeoutMs,
    )

    fun Skill.toEntity() = SkillEntity(
        id = id,
        name = name,
        description = description,
        parametersJson = parameters.toString(),
        code = code,
        language = language.name,
        version = version,
        enabled = enabled,
        permissions = permissions.joinToString(",") { it.name },
        createdAt = createdAt,
        updatedAt = updatedAt,
        authoredByModel = authoredByModel,
        lastRunAt = lastRunAt,
        lastRunOk = lastRunOk,
        lastError = lastError,
        timeoutMs = timeoutMs,
    )

    fun SkillRevisionEntity.toDomain() = SkillRevision(
        id = id,
        skillId = skillId,
        version = version,
        code = code,
        parameters = parseObject(parametersJson),
        createdAt = createdAt,
        note = note,
    )

    // --- change requests --------------------------------------------------

    fun ChangeRequestEntity.toDomain() = ChangeRequest(
        id = id,
        title = title,
        body = body,
        kind = runCatching { ChangeKind.valueOf(kind) }.getOrDefault(ChangeKind.FEATURE),
        createdAt = createdAt,
        status = runCatching { ChangeStatus.valueOf(status) }.getOrDefault(ChangeStatus.DRAFT),
        issueNumber = issueNumber,
        issueUrl = issueUrl,
        appVersion = appVersion,
        attachments = attachmentsJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(attachmentListSerializer, it) }.getOrNull() }
            .orEmpty(),
    )

    fun ChangeRequest.toEntity() = ChangeRequestEntity(
        id = id,
        title = title,
        body = body,
        kind = kind.name,
        createdAt = createdAt,
        status = status.name,
        issueNumber = issueNumber,
        issueUrl = issueUrl,
        appVersion = appVersion,
        attachmentsJson = if (attachments.isEmpty()) {
            ""
        } else {
            json.encodeToString(attachmentListSerializer, attachments)
        },
    )

    // --- models -----------------------------------------------------------

    fun ModelEntity.toDomain() = LocalModel(
        id = id,
        displayName = displayName,
        family = family,
        parameterCount = parameterCount,
        quantization = quantization,
        sizeBytes = sizeBytes,
        contextLength = contextLength,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        filePath = filePath,
        state = runCatching { ModelState.valueOf(state) }.getOrDefault(ModelState.AVAILABLE),
        downloadedBytes = downloadedBytes,
        chatTemplate = chatTemplate,
        supportsTools = supportsTools,
        supportsThinking = supportsThinking,
        notes = notes,
    )

    fun LocalModel.toEntity() = ModelEntity(
        id = id,
        displayName = displayName,
        family = family,
        parameterCount = parameterCount,
        quantization = quantization,
        sizeBytes = sizeBytes,
        contextLength = contextLength,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        filePath = filePath,
        state = state.name,
        downloadedBytes = downloadedBytes,
        chatTemplate = chatTemplate,
        supportsTools = supportsTools,
        supportsThinking = supportsThinking,
        notes = notes,
    )

    private fun parseObject(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }
}
