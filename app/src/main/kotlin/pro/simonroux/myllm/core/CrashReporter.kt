package pro.simonroux.myllm.core

import android.content.Context
import android.os.Build
import pro.simonroux.myllm.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's black box.
 *
 * A sideloaded app has no crash console behind it, and asking its owner to pull
 * logcat off the phone is not a debugging loop anyone sustains. So the stack
 * trace is written to a file the app itself can show and copy.
 *
 * It stays on the device. Nothing here uploads anything, which is the whole
 * reason this exists rather than a crash-reporting SDK: those are exactly the
 * dependency this app promises not to have.
 */
class CrashReporter(private val context: Context) {

    private val crashFile: File get() = File(context.filesDir, "last-crash.txt")

    private val logFile: File get() = File(context.filesDir, "problems.txt")

    /**
     * Records fatal exceptions, then hands them to whatever handler was already
     * installed so Android still does its normal thing. Swallowing the crash
     * would leave the process in a state nobody can reason about.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { crashFile.writeText(render("PLANTAGE", thread.name, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Records something that went wrong without killing the app: a failed
     * background task, a coroutine that threw. These are the failures that
     * otherwise leave no trace at all beyond a feature quietly not working.
     */
    fun recordNonFatal(context: String, throwable: Throwable) {
        runCatching {
            val entry = render("ERREUR", context, throwable)
            // Bounded: a failure that repeats every second must not fill the disk.
            val existing = if (logFile.isFile) logFile.readText().takeLast(MAX_LOG_CHARS) else ""
            logFile.writeText(existing + entry + "\n")
        }
    }

    fun lastCrash(): String? =
        crashFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun problems(): String? =
        logFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() {
        crashFile.delete()
        logFile.delete()
    }

    private fun render(kind: String, where: String, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("=== $kind ===")
            appendLine("quand    : ${TIMESTAMP.format(Date())}")
            appendLine("où       : $where")
            appendLine("version  : ${BuildConfig.VERSION_NAME}")
            appendLine("appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
        }
    }

    private companion object {
        val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
        const val MAX_LOG_CHARS = 20_000
    }
}
