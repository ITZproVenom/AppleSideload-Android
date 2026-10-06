package dev.applesideload.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val dark = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF00213F),
    secondary = Color(0xFF8FD3C7),
    background = Color(0xFF101216),
    surface = Color(0xFF171A20),
    surfaceVariant = Color(0xFF1F232B),
    error = Color(0xFFFF8A80)
)

private val light = lightColorScheme(
    primary = Color(0xFF1A5FCB),
    secondary = Color(0xFF2E7D6F),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECF2)
)

@Composable
fun AppleSideloadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        content = content
    )
}
