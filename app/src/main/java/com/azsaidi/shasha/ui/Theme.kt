package com.azsaidi.shasha.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ShashaColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00121F),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFD7F0FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1E232A),
    onSurfaceVariant = Color(0xFFA9B1BC),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF13161A),
    surfaceContainer = Color(0xFF161A1F),
    surfaceContainerHigh = Color(0xFF1C2127),
    surfaceContainerHighest = Color(0xFF232930),
    outline = Color(0xFF3A414B),
    outlineVariant = Color(0xFF2A3038),
    error = Color(0xFFF87171),
)

@Composable
fun ShashaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShashaColors, content = content)
}
