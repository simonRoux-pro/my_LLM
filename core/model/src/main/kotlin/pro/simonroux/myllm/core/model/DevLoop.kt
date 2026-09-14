package pro.simonroux.myllm.core.model

import kotlinx.serialization.Serializable

/**
 * A request to change the app itself, captured on the phone and handed to a
 * coding agent off-device.
 *
 * This is the slow half of self-modification: anything a runtime skill cannot do
 * because it needs new Kotlin, a new dependency or a new screen.
 */
@Serializable
data class ChangeRequest(
    val id: String,
    val title: String,
    val body: String,
    val kind: ChangeKind,
    val createdAt: Long,
    val status: ChangeStatus = ChangeStatus.DRAFT,
    /** Filled once pushed to GitHub. */
    val issueNumber: Int? = null,
    val issueUrl: String? = null,
    /** App version the request was written against, so the agent knows the baseline. */
    val appVersion: String = "",
    /** Logs, failing skill code, conversation excerpts. Attached verbatim. */
    val attachments: List<ChangeAttachment> = emptyList(),
)

@Serializable
enum class ChangeKind { FEATURE, BUG, REFACTOR, MODEL_SUPPORT, UI, PERFORMANCE }

@Serializable
enum class ChangeStatus { DRAFT, EXPORTED, PUSHED, IN_PROGRESS, DONE, REJECTED }

@Serializable
data class ChangeAttachment(
    val label: String,
    val content: String,
    val mimeType: String = "text/plain",
)

/** A release seen on the update channel. */
@Serializable
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: Long,
    val prerelease: Boolean,
)
