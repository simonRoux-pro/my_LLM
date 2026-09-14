package pro.simonroux.myllm.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.simonroux.myllm.agent.tools.ChangeRequestSink
import pro.simonroux.myllm.core.data.db.ChangeRequestDao
import pro.simonroux.myllm.core.data.db.Mappers.toDomain
import pro.simonroux.myllm.core.data.db.Mappers.toEntity
import pro.simonroux.myllm.core.model.ChangeKind
import pro.simonroux.myllm.core.model.ChangeRequest
import pro.simonroux.myllm.core.model.ChangeStatus
import java.util.UUID

/**
 * The queue of changes the app cannot make to itself at runtime.
 *
 * When the model hits a wall that a skill cannot get around, it writes the
 * request here instead of apologising. The user then exports it to a coding
 * agent, or pushes it as an issue, and the change comes back as a new build.
 */
class ChangeRequestRepository(
    private val dao: ChangeRequestDao,
    private val appVersion: String,
) : ChangeRequestSink {

    fun observeAll(): Flow<List<ChangeRequest>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun submit(title: String, body: String, kind: String): String {
        val request = ChangeRequest(
            id = UUID.randomUUID().toString(),
            title = title,
            body = body,
            kind = runCatching { ChangeKind.valueOf(kind.uppercase()) }
                .getOrDefault(ChangeKind.FEATURE),
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
        )
        dao.upsert(request.toEntity())
        return request.id
    }

    suspend fun save(request: ChangeRequest) = dao.upsert(request.toEntity())

    suspend fun byId(id: String): ChangeRequest? = dao.byId(id)?.toDomain()

    suspend fun setStatus(id: String, status: ChangeStatus) {
        val existing = byId(id) ?: return
        dao.upsert(existing.copy(status = status).toEntity())
    }

    suspend fun markPushed(id: String, issueNumber: Int, issueUrl: String) =
        dao.markPushed(id, ChangeStatus.PUSHED.name, issueNumber, issueUrl)

    suspend fun delete(id: String) = dao.delete(id)

    /**
     * Renders a request as a brief a coding agent can act on without the phone
     * in front of it.
     *
     * The format is plain Markdown on purpose: it is pasted into a terminal,
     * a chat window or an issue body, and all three mangle anything richer.
     */
    fun renderBrief(request: ChangeRequest): String = buildString {
        appendLine("# ${request.title}")
        appendLine()
        appendLine("Type : ${request.kind.name}")
        appendLine("Version de l'app : ${request.appVersion.ifBlank { "inconnue" }}")
        appendLine("Dépôt : simonroux-pro/my_llm")
        appendLine()
        appendLine("## Demande")
        appendLine()
        appendLine(request.body)
        if (request.attachments.isNotEmpty()) {
            appendLine()
            appendLine("## Contexte joint")
            request.attachments.forEach { attachment ->
                appendLine()
                appendLine("### ${attachment.label}")
                appendLine()
                appendLine("```")
                appendLine(attachment.content.take(4000))
                appendLine("```")
            }
        }
        appendLine()
        appendLine("## Attendu")
        appendLine()
        appendLine("- Implémenter la demande dans le module concerné.")
        appendLine("- Garder l'app fonctionnelle hors ligne.")
        appendLine("- Ne pas ajouter de télémétrie ni de dépendance réseau non demandée.")
        appendLine("- Vérifier que `./gradlew assembleRelease` passe.")
    }
}
