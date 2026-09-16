package pro.simonroux.myllm.core.data

import android.content.Context
import pro.simonroux.myllm.agent.ToolHost
import pro.simonroux.myllm.core.data.db.MyLlmDatabase
import pro.simonroux.myllm.core.data.repo.AndroidToolHost
import pro.simonroux.myllm.core.data.repo.ChangeRequestRepository
import pro.simonroux.myllm.core.data.repo.ChatRepository
import pro.simonroux.myllm.core.data.repo.ModelDownloader
import pro.simonroux.myllm.core.data.repo.ModelRepository
import pro.simonroux.myllm.core.data.repo.NoteRepository
import pro.simonroux.myllm.core.data.repo.SkillRepository
import pro.simonroux.myllm.core.data.store.SecretStore
import pro.simonroux.myllm.core.data.store.SettingsStore

/**
 * Everything stored on the device, assembled behind repository types.
 *
 * The database and its DAOs stay private here. Handing them out would put Room
 * on the compile classpath of every module that wants a conversation, and the
 * UI would then be one autocomplete away from running a query on the main
 * thread. Callers get repositories, which is the whole vocabulary they need.
 */
class DataModule(
    private val context: Context,
    private val appVersion: String,
) {

    private val database by lazy { MyLlmDatabase.create(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val secrets: SecretStore by lazy { SecretStore(context) }

    val chat: ChatRepository by lazy {
        ChatRepository(database.conversations(), database.messages())
    }

    val skills: SkillRepository by lazy { SkillRepository(database.skills()) }

    val notes: NoteRepository by lazy { NoteRepository(database.notes()) }

    val models: ModelRepository by lazy { ModelRepository(context, database.models()) }

    val downloader: ModelDownloader by lazy { ModelDownloader(models) }

    val changeRequests: ChangeRequestRepository by lazy {
        ChangeRequestRepository(database.changeRequests(), appVersion)
    }

    /**
     * Builds the bridge skills use to reach the device.
     *
     * A factory rather than a property because the two behaviours it needs,
     * reading the live offline setting and running a sub-completion, are
     * decided by the layer that owns the engines.
     */
    fun createToolHost(
        offlineOnly: suspend () -> Boolean,
        subCompletion: suspend (String, Int) -> String,
    ): ToolHost = AndroidToolHost(
        context = context,
        skillMemory = database.skillMemory(),
        offlineOnly = offlineOnly,
        subCompletion = subCompletion,
    )
}
