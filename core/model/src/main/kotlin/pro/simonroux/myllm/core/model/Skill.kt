package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A tool written in script rather than compiled into the app.
 *
 * Skills are the hot path for self-modification: the agent can create, edit and
 * test one without a rebuild, and everything about it is versioned and revertible.
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    /** JSON Schema for the arguments the model passes in. */
    val parameters: JsonObject,
    val code: String,
    val language: SkillLanguage = SkillLanguage.JAVASCRIPT,
    val version: Int = 1,
    val enabled: Boolean = true,
    val permissions: Set<SkillPermission> = emptySet(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Set when the agent wrote this skill rather than the user. */
    val authoredByModel: Boolean = false,
    val lastRunAt: Long? = null,
    val lastRunOk: Boolean? = null,
    val lastError: String? = null,
    /** Wall clock ceiling for one execution. */
    val timeoutMs: Long = 5_000,
)

@Serializable
enum class SkillLanguage { JAVASCRIPT }

/**
 * Capabilities a skill must declare up front. Anything not declared is not
 * reachable from the sandbox, so a skill cannot silently widen its own reach.
 */
@Serializable
enum class SkillPermission {
    /** Outbound HTTP. Blocked entirely while the app is in offline mode. */
    NETWORK,

    /** Read and write inside the skill's own private directory, nowhere else. */
    STORAGE,

    /** Key/value store shared between runs of the same skill. */
    MEMORY,

    /** Re-enter the LLM for a sub-completion. */
    LLM,

    /** Post a system notification. */
    NOTIFY,

    /** Read device clock, locale, battery and connectivity. */
    DEVICE_INFO,

    /** Read and write other skills. Reserved for the self-modification tools. */
    SKILL_ADMIN,
}

/** One revision of a skill, kept so a bad edit can always be rolled back. */
@Serializable
data class SkillRevision(
    val id: String,
    val skillId: String,
    val version: Int,
    val code: String,
    val parameters: JsonObject,
    val createdAt: Long,
    val note: String = "",
)
