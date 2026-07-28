package com.kennyb1201.kbstream.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val KBStreamColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    background = Color(0xFF0D1B2A),
    surface = Color(0xFF0D1B2A),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun KBStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KBStreamColorScheme,
        content = content
    )
}
