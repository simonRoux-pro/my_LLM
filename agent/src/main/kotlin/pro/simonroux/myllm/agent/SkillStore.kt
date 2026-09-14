package pro.simonroux.myllm.agent

import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillRevision

/**
 * Persistence for skills, seen from the agent side.
 *
 * Declared here rather than in the data layer so the agent module stays free of
 * Android and can be exercised against an in-memory implementation in tests.
 */
interface SkillStore {

    suspend fun all(): List<Skill>

    suspend fun enabled(): List<Skill>

    suspend fun byName(name: String): Skill?

    suspend fun byId(id: String): Skill?

    /**
     * Creates or replaces a skill, bumping its version and keeping the previous
     * body as a revision. Every write is recoverable; that is what makes letting
     * a model edit the app acceptable.
     */
    suspend fun upsert(skill: Skill, note: String = ""): Skill

    suspend fun delete(id: String): Boolean

    suspend fun setEnabled(id: String, enabled: Boolean)

    suspend fun revisions(skillId: String): List<SkillRevision>

    /** Rolls a skill back to an earlier revision, itself recorded as a new version. */
    suspend fun restore(skillId: String, version: Int): Skill?

    suspend fun recordRun(id: String, ok: Boolean, error: String?)
}
