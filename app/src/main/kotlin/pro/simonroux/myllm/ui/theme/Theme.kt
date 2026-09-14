package pro.simonroux.myllm.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import pro.simonroux.myllm.core.model.ThemeMode

/**
 * A calm, low-contrast palette that reads well on an AMOLED panel at night.
 *
 * This is a tool someone uses in bed and on a train, so the dark scheme is the
 * one that was designed and the light one derived from it. BLACK is a separate
 * mode rather than a tweak: true black actually saves power on this screen and
 * is the one people ask for.
 */
private val Blue = Color(0xFF7AA2F7)
private val Green = Color(0xFF9ECE6A)
private val Red = Color(0xFFF7768E)
private val Violet = Color(0xFFBB9AF7)

private val DarkScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF1E2B45),
    onPrimaryContainer = Color(0xFFCBDAFB),
    secondary = Violet,
    onSecondary = Color(0xFF17102A),
    secondaryContainer = Color(0xFF2A2140),
    onSecondaryContainer = Color(0xFFE2D5FA),
    tertiary = Green,
    onTertiary = Color(0xFF0D1508),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFDCE0E8),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFDCE0E8),
    surfaceVariant = Color(0xFF1C2028),
    onSurfaceVariant = Color(0xFF9AA2B1),
    outline = Color(0xFF2E3440),
    outlineVariant = Color(0xFF232833),
    error = Red,
    onError = Color(0xFF1A0A0E),
    errorContainer = Color(0xFF3C1D25),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val BlackScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF07080A),
    surfaceVariant = Color(0xFF121418),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E5FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3FF),
    onPrimaryContainer = Color(0xFF0A1F46),
    secondary = Color(0xFF6B4FBF),
    tertiary = Color(0xFF3F7A2E),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF15171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFEEF0F5),
    onSurfaceVariant = Color(0xFF515766),
    outline = Color(0xFFC6CAD4),
    error = Color(0xFFBA1A2B),
)

/**
 * Slightly tighter than the Material default.
 *
 * Chat is dense text and the stock line height wastes a lot of a phone screen
 * on whitespace, which means more scrolling per answer.
 */
private val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun MyLlmTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }

    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colors = when {
        // Material You is skipped for BLACK: extracted colours come with their
        // own surface tints, which is exactly what that mode exists to avoid.
        dynamicColor && supportsDynamic && themeMode != ThemeMode.BLACK ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        themeMode == ThemeMode.BLACK -> BlackScheme
        dark -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
