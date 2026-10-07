package dev.applesideload.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A complete Material 3 scheme from one blue seed, so no role falls back to
// the library's purple defaults. Android 12 and later use the wallpaper's
// colours instead (dynamic colour).
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B61A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA0CAFF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA0CAFF),
    onPrimary = Color(0xFF003259),
    primaryContainer = Color(0xFF00497E),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253141),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = Color(0xFF0B61A4),
    surfaceDim = Color(0xFF111418),
    surfaceBright = Color(0xFF36393E),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A)
)

/** Colours Material 3 has no role for: a finished or healthy state, and one that needs the user. */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightStatus = StatusColors(
    success = Color(0xFF146C2E),
    successContainer = Color(0xFFB8F1BF),
    onSuccessContainer = Color(0xFF002107),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF271900)
)

private val DarkStatus = StatusColors(
    success = Color(0xFF9DD5A4),
    successContainer = Color(0xFF1F5130),
    onSuccessContainer = Color(0xFFB8F1BF),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDEA6)
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The app's colours beyond [MaterialTheme]'s. */
object AppColors {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

/**
 * The app's theme: dynamic colour where Android has it (12 and later),
 * the blue scheme above elsewhere, light or dark with the system.
 */
@Composable
fun AppleSideloadTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
