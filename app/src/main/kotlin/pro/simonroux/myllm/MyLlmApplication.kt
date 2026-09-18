package pro.simonroux.myllm

import android.app.Application
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pro.simonroux.myllm.core.AppContainer
import pro.simonroux.myllm.core.CrashReporter

class MyLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var crashReporter: CrashReporter
        private set

    /**
     * Startup work must not be able to kill the app.
     *
     * Without a handler, anything thrown inside this scope reaches the default
     * uncaught handler and takes the process down before the first screen is
     * drawn, which is indistinguishable from the app simply not working. With
     * one, the failure is recorded and visible in the Évolutions tab while the
     * rest of the app carries on.
     */
    private val scope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    crashReporter.recordNonFatal("démarrage", throwable)
                },
        )
    }

    override fun onCreate() {
        super.onCreate()

        crashReporter = CrashReporter(this)
        crashReporter.install()

        container = AppContainer(this, crashReporter)

        // Off the main thread: seeding the catalog and reconciling the model
        // directory both touch disk, and doing that in onCreate is a visible
        // stutter on a cold start.
        scope.launch { container.initialise() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // A loaded 7B model is several gigabytes. When the system says it is
        // under pressure, giving the weights back is the difference between the
        // app being backgrounded and being killed outright.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            scope.launch { container.engineManager.releaseLocal() }
        }
    }
}
