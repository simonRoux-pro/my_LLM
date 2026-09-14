package pro.simonroux.myllm

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pro.simonroux.myllm.core.AppContainer

class MyLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

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
