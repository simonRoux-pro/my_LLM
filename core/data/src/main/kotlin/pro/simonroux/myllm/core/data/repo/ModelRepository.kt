package pro.simonroux.myllm.core.data.repo

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.simonroux.myllm.core.data.db.Mappers.toDomain
import pro.simonroux.myllm.core.data.db.Mappers.toEntity
import pro.simonroux.myllm.core.data.db.ModelDao
import pro.simonroux.myllm.core.model.LocalModel
import pro.simonroux.myllm.core.model.ModelState
import java.io.File

/**
 * The GGUF files on the device and the catalog of ones that could be.
 *
 * Weights live in the app's private files directory rather than shared storage:
 * a multi-gigabyte file in Downloads is one cleanup app away from disappearing,
 * and a model the user paid bandwidth for should not be readable by every other
 * app on the phone.
 */
class ModelRepository(
    private val context: Context,
    private val dao: ModelDao,
) {

    val modelsDirectory: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun observeAll(): Flow<List<LocalModel>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeReady(): Flow<List<LocalModel>> =
        dao.observeReady().map { list -> list.map { it.toDomain() } }

    suspend fun byId(id: String): LocalModel? = dao.byId(id)?.toDomain()

    suspend fun all(): List<LocalModel> = dao.all().map { it.toDomain() }

    suspend fun save(model: LocalModel) = dao.upsert(model.toEntity())

    /**
     * Merges the built-in catalog in without touching anything already on disk.
     *
     * A catalog refresh must never flip a downloaded model back to AVAILABLE, so
     * entries that exist locally keep their state, path and progress.
     */
    suspend fun seedCatalog(catalog: List<LocalModel>) {
        val existing = dao.all().associateBy { it.id }
        val merged = catalog.map { entry ->
            val current = existing[entry.id]
            if (current == null) {
                entry.toEntity()
            } else {
                entry.toEntity().copy(
                    state = current.state,
                    filePath = current.filePath,
                    downloadedBytes = current.downloadedBytes,
                    chatTemplate = current.chatTemplate ?: entry.chatTemplate,
                )
            }
        }
        dao.upsertAll(merged)
    }

    fun fileFor(model: LocalModel): File = File(modelsDirectory, "${model.id}.gguf")

    suspend fun updateProgress(id: String, state: ModelState, downloadedBytes: Long) =
        dao.updateProgress(id, state.name, downloadedBytes)

    suspend fun markReady(id: String, path: String) = dao.markReady(id, ModelState.READY.name, path)

    suspend fun markFailed(id: String) = dao.markReady(id, ModelState.FAILED.name, null)

    /** Removes the weights but keeps the catalog entry, so it can be fetched again. */
    suspend fun deleteWeights(id: String) {
        val model = byId(id) ?: return
        model.filePath?.let { File(it).delete() }
        fileFor(model).delete()
        dao.updateProgress(id, ModelState.AVAILABLE.name, 0)
        dao.markReady(id, ModelState.AVAILABLE.name, null)
    }

    /**
     * Reconciles the database with what is actually on disk.
     *
     * Android can delete an app's cache and files under storage pressure, and a
     * model row pointing at a file that is gone would fail at load time with a
     * confusing error rather than simply offering to download it again.
     */
    suspend fun reconcile() {
        dao.all().forEach { entity ->
            val path = entity.filePath ?: return@forEach
            if (entity.state == ModelState.READY.name && !File(path).isFile) {
                dao.updateProgress(entity.id, ModelState.AVAILABLE.name, 0)
                dao.markReady(entity.id, ModelState.AVAILABLE.name, null)
            }
        }
    }

    fun freeSpaceBytes(): Long = modelsDirectory.usableSpace
}
