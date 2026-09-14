package pro.simonroux.myllm.core.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.simonroux.myllm.agent.SkillStore
import pro.simonroux.myllm.core.data.db.Mappers.toDomain
import pro.simonroux.myllm.core.data.db.Mappers.toEntity
import pro.simonroux.myllm.core.data.db.SkillDao
import pro.simonroux.myllm.core.data.db.SkillRevisionEntity
import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillRevision
import java.util.UUID

/**
 * Skill persistence, with every write recorded as a revision.
 *
 * The revision history is what makes it reasonable to let a model edit its own
 * tools: a bad edit is one call away from being undone, and the user can read
 * exactly what changed between two versions.
 */
class SkillRepository(private val dao: SkillDao) : SkillStore {

    fun observeAll(): Flow<List<Skill>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun all(): List<Skill> = dao.all().map { it.toDomain() }

    override suspend fun enabled(): List<Skill> = dao.enabled().map { it.toDomain() }

    override suspend fun byName(name: String): Skill? = dao.byName(name)?.toDomain()

    override suspend fun byId(id: String): Skill? = dao.byId(id)?.toDomain()

    override suspend fun upsert(skill: Skill, note: String): Skill {
        dao.upsertWithRevision(
            skill = skill.toEntity(),
            revision = SkillRevisionEntity(
                id = UUID.randomUUID().toString(),
                skillId = skill.id,
                version = skill.version,
                code = skill.code,
                parametersJson = skill.parameters.toString(),
                createdAt = skill.updatedAt,
                note = note,
            ),
        )
        return skill
    }

    override suspend fun delete(id: String): Boolean = dao.delete(id) > 0

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun revisions(skillId: String): List<SkillRevision> =
        dao.revisions(skillId).map { it.toDomain() }

    override suspend fun restore(skillId: String, version: Int): Skill? {
        val current = dao.byId(skillId)?.toDomain() ?: return null
        val revision = dao.revision(skillId, version) ?: return null

        // A restore is itself a new version. Rolling back by rewriting history
        // would make the log lie about what the app was running when.
        val restored = current.copy(
            code = revision.code,
            parameters = revision.toDomain().parameters,
            version = current.version + 1,
            updatedAt = System.currentTimeMillis(),
            lastError = null,
        )
        return upsert(restored, note = "Restauration de la version $version")
    }

    override suspend fun recordRun(id: String, ok: Boolean, error: String?) =
        dao.recordRun(id, System.currentTimeMillis(), ok, error)
}
