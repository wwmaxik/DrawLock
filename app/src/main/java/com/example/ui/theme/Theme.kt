package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DrawLockColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF002026),
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = Color(0xFFB6F5FF),
    secondary = NeonCoral,
    onSecondary = Color(0xFF4A0014),
    secondaryContainer = Color(0xFF700022),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = ElectricYellow,
    onTertiary = Color(0xFF332E00),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder
)

@Composable
fun DrawLockTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DrawLockColorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    DrawLockTheme(content = content)
}
