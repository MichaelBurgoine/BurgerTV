package com.example.tvplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Slate-blue base with a warm amber focus colour that reads clearly from the sofa.
private val Colors = darkColorScheme(
    primary = Color(0xFFF2B33D),
    onPrimary = Color(0xFF1B1405),
    background = Color(0xFF10192B),
    onBackground = Color(0xFFEEF1F6),
    surface = Color(0xFF18243A),
    onSurface = Color(0xFFEEF1F6),
    surfaceVariant = Color(0xFF223049),
    onSurfaceVariant = Color(0xFF9AA6BC),
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
