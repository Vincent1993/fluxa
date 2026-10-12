package com.fluxa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(primary = Color(0xFF92734A), onPrimary = Color.White,
    background = Color(0xFFFAF8F3), surface = Color(0xFFFAF8F3), onSurface = Color(0xFF24221F),
    onSurfaceVariant = Color(0xFF77716A), surfaceVariant = Color(0xFFF0EDE7), outlineVariant = Color(0xFFE2DDD4))
private val DarkColors = darkColorScheme(primary = Color(0xFFD9B778), background = Color(0xFF191816),
    surface = Color(0xFF191816), onSurface = Color(0xFFE4E0D8), onSurfaceVariant = Color(0xFFABA49A), outlineVariant = Color(0xFF38342F))

@Composable
fun FluxaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = FluxaTypography, content = content)
}
