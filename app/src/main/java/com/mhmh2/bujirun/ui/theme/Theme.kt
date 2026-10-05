package com.mhmh2.bujirun.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Placeholder palette (an energetic orange accent, common for running apps) - to be replaced
// once the app's real design is decided. Follows the system light/dark setting, since a running
// app is used outdoors in bright daylight as often as at night.
private val RunOrange = Color(0xFFFF6A2B)

private val LightColors = lightColorScheme(
    primary = RunOrange,
    onPrimary = Color.White
)

private val DarkColors = darkColorScheme(
    primary = RunOrange,
    onPrimary = Color.White
)

@Composable
fun BujirunTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
