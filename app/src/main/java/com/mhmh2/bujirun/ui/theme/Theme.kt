package com.mhmh2.bujirun.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Placeholder palette (an energetic orange accent, common for running apps) - to be replaced
// once the app's real design is decided. Follows the system light/dark setting, since a running
// app is used outdoors in bright daylight as often as at night. The neutrals are warmed to match
// the orange; Material's defaults are lavender-tinted and clashed with it.
private val RunOrange = Color(0xFFFF6A2B)

private val LightColors = lightColorScheme(
    primary = RunOrange,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF5E6DF),
    onSurfaceVariant = Color(0xFF53433C),
    surfaceContainerHighest = Color(0xFFF5E6DF),
    surfaceContainer = Color(0xFFFBEEE8),     // bottom navigation bar
    surfaceContainerHigh = Color(0xFFF8EAE3),  // dialogs
    surfaceContainerLow = Color(0xFFFEF4F0),
)

private val DarkColors = darkColorScheme(
    primary = RunOrange,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF5C2A14),
    onSecondaryContainer = Color(0xFFFFDBCC),
    background = Color(0xFF1A1110),
    surface = Color(0xFF1A1110),
    surfaceVariant = Color(0xFF3B2E29),
    onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceContainerHighest = Color(0xFF3B2E29),
    surfaceContainer = Color(0xFF271D1A),
    surfaceContainerHigh = Color(0xFF322723),
    surfaceContainerLow = Color(0xFF221916),
)

@Composable
fun BujirunTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
