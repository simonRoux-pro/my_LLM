package pro.simonroux.myllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import pro.simonroux.myllm.core.model.AppSettings
import pro.simonroux.myllm.ui.MyLlmApp
import pro.simonroux.myllm.ui.theme.MyLlmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val container = (application as MyLlmApplication).container

        setContent {
            val settings by container.settingsStore.settings
                .collectAsState(initial = AppSettings())

            MyLlmTheme(
                themeMode = settings.appearance.theme,
                dynamicColor = settings.appearance.dynamicColor,
            ) {
                MyLlmApp(container = container, settings = settings)
            }
        }
    }
}
