package pro.simonroux.myllm.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.simonroux.myllm.BuildConfig
import pro.simonroux.myllm.core.data.store.SettingsStore
import pro.simonroux.myllm.core.model.AvailableUpdate
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases.
 *
 * This is the other half of self-modification: what a runtime skill cannot do
 * because it needs new Kotlin comes back as a release built by CI, and the app
 * installs it itself. Sideloading rather than a store listing is deliberate, the
 * app is personal and its release cadence is however often its owner asks for a
 * change.
 *
 * The install still goes through the system installer, so the user confirms
 * every update and Android verifies the signature. An app that could replace
 * itself silently would be indistinguishable from malware.
 */
class Updater(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val updatesDirectory: File
        get() = File(context.filesDir, "updates").apply { mkdirs() }

    /** Returns null when the app is already current, or when offline. */
    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        if (settings.offlineOnly) return@withContext null

        val repository = settings.updates.repository.ifBlank { return@withContext null }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .build()

        val payload = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull() ?: return@withContext null

        val releases: JsonArray = runCatching { json.parseToJsonElement(payload).jsonArray }
            .getOrNull() ?: return@withContext null

        settingsStore.update { current ->
            current.copy(updates = current.updates.copy(lastCheckedAt = System.currentTimeMillis()))
        }

        releases.asSequence()
            .mapNotNull { element ->
                val release = element.jsonObject
                val prerelease = release["prerelease"]?.jsonPrimitive?.content == "true"
                if (prerelease && !settings.updates.includePrereleases) return@mapNotNull null

                val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val asset = release["assets"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?: return@mapNotNull null

                AvailableUpdate(
                    versionName = tag.removePrefix("v"),
                    versionCode = tag.toVersionCode(),
                    releaseNotes = release["body"]?.jsonPrimitive?.content.orEmpty(),
                    apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null,
                    apkSizeBytes = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    publishedAt = System.currentTimeMillis(),
                    prerelease = prerelease,
                )
            }
            .firstOrNull { isNewerThanInstalled(it.versionName) }
    }

    fun download(update: AvailableUpdate): Flow<UpdateProgress> = flow {
        val target = File(updatesDirectory, "myllm-${update.versionName}.apk")
        if (target.isFile && target.length() == update.apkSizeBytes && update.apkSizeBytes > 0) {
            emit(UpdateProgress.Ready(target))
            return@flow
        }

        emit(UpdateProgress.Downloading(0, update.apkSizeBytes))

        val request = Request.Builder().url(update.apkUrl).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(UpdateProgress.Failed("HTTP ${response.code}"))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(UpdateProgress.Failed("Réponse vide"))
                    return@flow
                }

                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            emit(UpdateProgress.Downloading(written, update.apkSizeBytes))
                        }
                    }
                }
            }
        }.getOrElse {
            target.delete()
            emit(UpdateProgress.Failed(it.message ?: "Téléchargement interrompu"))
            return@flow
        }

        emit(UpdateProgress.Ready(target))
    }.flowOn(Dispatchers.IO)

    /**
     * Hands the APK to the system installer.
     *
     * A FileProvider URI rather than a file path: since Android 7 a file:// URI
     * to another process throws, and the installer is another process.
     */
    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_PENDING_USER_ACTION,
            )
        }
        context.startActivity(intent)
    }

    /** Frees the downloaded APKs once an update has been applied. */
    fun cleanUp() {
        updatesDirectory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Compares dotted version strings numerically.
     *
     * String comparison would rank 0.10.0 below 0.9.0, which is exactly the
     * point at which an updater silently stops offering updates.
     */
    private fun isNewerThanInstalled(candidate: String): Boolean {
        val installed = BuildConfig.VERSION_NAME.substringBefore('-')
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = installed.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun String.toVersionCode(): Long {
        val parts = removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        return parts.getOrElse(0) { 0 } * 1_000_000L +
            parts.getOrElse(1) { 0 } * 1_000L +
            parts.getOrElse(2) { 0 }
    }
}

sealed interface UpdateProgress {
    data class Downloading(val bytes: Long, val total: Long) : UpdateProgress
    data class Ready(val apk: File) : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
}
