package pro.simonroux.myllm.core.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.simonroux.myllm.core.model.LocalModel
import pro.simonroux.myllm.core.model.ModelState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches GGUF weights, resumably.
 *
 * A model is several gigabytes over a mobile connection, so an interrupted
 * download that has to start over is not an inconvenience, it is the difference
 * between the feature working and not. Bytes go to a `.part` file and the range
 * request picks up where it stopped; the file is only renamed once complete.
 */
class ModelDownloader(private val repository: ModelRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout: a slow but live transfer is still progress, and
        // killing it would throw away everything since the last resume point.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun download(model: LocalModel): Flow<DownloadProgress> = flow {
        if (model.downloadUrl.isBlank()) {
            emit(DownloadProgress.Failed("Aucune URL pour ${model.displayName}"))
            return@flow
        }

        val target = repository.fileFor(model)
        val partial = File(target.parentFile, target.name + ".part")
        val alreadyHave = if (partial.isFile) partial.length() else 0L

        emit(DownloadProgress.Started(alreadyHave, model.sizeBytes))
        repository.updateProgress(model.id, ModelState.DOWNLOADING, alreadyHave)

        val request = Request.Builder()
            .url(model.downloadUrl)
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // 200 to a ranged request means the server ignored the range and
                // is sending the whole file, so the partial data is stale.
                val restarting = alreadyHave > 0 && response.code == 200
                if (restarting) partial.delete()

                if (!response.isSuccessful) {
                    repository.updateProgress(model.id, ModelState.FAILED, alreadyHave)
                    emit(DownloadProgress.Failed("HTTP ${response.code}"))
                    return@flow
                }

                val body = response.body ?: run {
                    emit(DownloadProgress.Failed("Réponse vide"))
                    return@flow
                }

                val offset = if (restarting) 0L else alreadyHave
                val total = if (body.contentLength() > 0) body.contentLength() + offset else model.sizeBytes

                partial.parentFile?.mkdirs()
                var written = offset
                var lastReport = 0L

                body.byteStream().use { input ->
                    java.io.RandomAccessFile(partial, "rw").use { output ->
                        output.seek(offset)
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read

                            // Emitting on every buffer would flood the UI and the
                            // database; once a second is enough to look live.
                            val now = System.currentTimeMillis()
                            if (now - lastReport > PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                repository.updateProgress(model.id, ModelState.DOWNLOADING, written)
                                emit(DownloadProgress.Downloading(written, total))
                            }
                        }
                    }
                }

                if (model.sha256 != null) {
                    emit(DownloadProgress.Verifying)
                    repository.updateProgress(model.id, ModelState.VERIFYING, written)
                    val actual = sha256(partial)
                    if (!actual.equals(model.sha256, ignoreCase = true)) {
                        partial.delete()
                        repository.markFailed(model.id)
                        emit(DownloadProgress.Failed("Empreinte SHA-256 incorrecte, fichier supprimé"))
                        return@flow
                    }
                }

                if (!partial.renameTo(target)) {
                    repository.markFailed(model.id)
                    emit(DownloadProgress.Failed("Impossible de finaliser le fichier"))
                    return@flow
                }

                repository.markReady(model.id, target.absolutePath)
                emit(DownloadProgress.Completed(target.absolutePath, written))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The partial file is kept on purpose: cancelling is how the user
            // pauses, and the next attempt resumes from here.
            repository.updateProgress(model.id, ModelState.PAUSED, partial.length())
            throw e
        } catch (e: Exception) {
            repository.updateProgress(model.id, ModelState.PAUSED, partial.length())
            emit(DownloadProgress.Failed(e.message ?: "Téléchargement interrompu"))
        }
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_MS = 1_000
    }
}

sealed interface DownloadProgress {
    data class Started(val resumedFrom: Long, val total: Long) : DownloadProgress
    data class Downloading(val bytes: Long, val total: Long) : DownloadProgress
    data object Verifying : DownloadProgress
    data class Completed(val path: String, val bytes: Long) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}
