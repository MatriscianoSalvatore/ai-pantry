package com.smatrisciano.aipantry.core.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7EDB8F),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFB9F0C0),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF442B00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF171C16),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF23291F),
    onSurfaceVariant = Color(0xFFC2C9BC),
    outline = Color(0xFF8C9388)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0C0),
    onPrimaryContainer = Color(0xFF002106),
    secondary = Color(0xFFEF6C00),
    onSecondary = Color.White,
    background = Color(0xFFF8FAF3),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFFDFDF6),
    onSurface = Color(0xFF1A1C18)
)

@Composable
fun AiPantryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
