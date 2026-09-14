package pro.simonroux.myllm.core.data.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.simonroux.myllm.agent.HostHttpResponse
import pro.simonroux.myllm.agent.ToolHost
import pro.simonroux.myllm.core.data.db.SkillMemoryDao
import pro.simonroux.myllm.core.data.db.SkillMemoryEntity
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The device, as skills and tools are allowed to see it.
 *
 * Every capability is narrowed here rather than in the caller: a skill gets its
 * own directory, its own slice of the key/value store and an HTTP client with a
 * short leash. Nothing in this class takes a path or a namespace from the
 * script without rewriting it first.
 */
class AndroidToolHost(
    private val context: Context,
    private val skillMemory: SkillMemoryDao,
    /** Consults the live setting on every call, not a snapshot taken at startup. */
    private val offlineOnly: suspend () -> Boolean,
    /** Runs a sub-completion. Injected because the engine is chosen at runtime. */
    private val subCompletion: suspend (String, Int) -> String,
) : ToolHost {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun networkAllowed(): Boolean = !offlineOnly() && isConnected()

    override suspend fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HostHttpResponse = withContext(Dispatchers.IO) {
        if (!networkAllowed()) {
            return@withContext HostHttpResponse(0, "Mode hors ligne actif")
        }

        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            // Header injection through a script-supplied name would let a skill
            // forge a request line; reject rather than sanitise.
            if (name.none { it == '\n' || it == '\r' }) builder.addHeader(name, value)
        }

        when (method.uppercase(Locale.ROOT)) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete(body?.toRequestBody())
            "PUT" -> builder.put((body ?: "").toRequestBody())
            "PATCH" -> builder.patch((body ?: "").toRequestBody())
            else -> builder.post((body ?: "").toRequestBody())
        }

        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                HostHttpResponse(
                    status = response.code,
                    // Bounded on purpose: an unbounded read of a large file from
                    // a script would take the process down with an OOM.
                    body = response.body?.source()?.let { source ->
                        source.readUtf8(minOf(response.body?.contentLength() ?: MAX_BODY_BYTES, MAX_BODY_BYTES))
                    }.orEmpty(),
                    headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                )
            }
        }.getOrElse { HostHttpResponse(0, "Erreur réseau : ${it.message}") }
    }

    // --- skill-scoped storage ---------------------------------------------

    override suspend fun readMemory(namespace: String, key: String): String? =
        skillMemory.read(namespace, key)

    override suspend fun writeMemory(namespace: String, key: String, value: String?) {
        if (value == null) {
            skillMemory.remove(namespace, key)
        } else {
            skillMemory.write(SkillMemoryEntity(namespace, key, value, System.currentTimeMillis()))
        }
    }

    override suspend fun listMemoryKeys(namespace: String): List<String> = skillMemory.keys(namespace)

    override suspend fun readFile(namespace: String, relativePath: String): String? =
        withContext(Dispatchers.IO) {
            val file = resolve(namespace, relativePath) ?: return@withContext null
            if (file.isFile) file.readText() else null
        }

    override suspend fun writeFile(namespace: String, relativePath: String, content: String) =
        withContext(Dispatchers.IO) {
            val file = resolve(namespace, relativePath) ?: return@withContext
            file.parentFile?.mkdirs()
            file.writeText(content)
        }

    override suspend fun listFiles(namespace: String): List<String> = withContext(Dispatchers.IO) {
        val root = skillDirectory(namespace)
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            .toList()
    }

    // --- device ------------------------------------------------------------

    override suspend fun notify(title: String, body: String) {
        if (!hasNotificationPermission()) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Skills", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications émises par les skills"
            },
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
        }
    }

    override suspend fun deviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging ?: false
        val files = context.filesDir

        buildMap {
            put("modele", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("langue", Locale.getDefault().toLanguageTag())
            put("batterie", if (level >= 0) "$level%" else "inconnue")
            put("en_charge", if (charging) "oui" else "non")
            put("reseau", networkDescription())
            put("hors_ligne_force", if (offlineOnly()) "oui" else "non")
            put("stockage_libre_go", "%.1f".format(files.usableSpace / 1_000_000_000.0))
            put("coeurs", Runtime.getRuntime().availableProcessors().toString())
        }
    }

    override suspend fun askModel(prompt: String, maxTokens: Int): String =
        subCompletion(prompt, maxTokens)

    override fun log(namespace: String, message: String) {
        Log.d("skill/$namespace", message)
    }

    // --- internals ---------------------------------------------------------

    private fun skillDirectory(namespace: String): File {
        // The namespace comes from a skill id, but sanitising it here means a
        // hand-edited database row cannot turn into a path escape either.
        val safe = namespace.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(context.filesDir, "skills"), safe).apply { mkdirs() }
    }

    /**
     * Resolves a skill-relative path, returning null if it lands outside the
     * skill's own directory.
     *
     * Canonical paths are compared rather than the raw strings, so a symlink or
     * an encoded traversal cannot slip through.
     */
    private fun resolve(namespace: String, relativePath: String): File? {
        val root = skillDirectory(namespace).canonicalFile
        val target = File(root, relativePath).canonicalFile
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            target
        } else {
            null
        }
    }

    private fun isConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun networkDescription(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "indisponible"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "hors ligne"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "autre"
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "myllm_skills"
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
    }
}
