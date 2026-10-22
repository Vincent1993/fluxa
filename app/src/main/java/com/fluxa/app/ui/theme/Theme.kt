package com.fluxa.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xff286347), onPrimary = Color.White,
    primaryContainer = Color(0xffccebd6), onPrimaryContainer = Color(0xff123b27),
    secondary = Color(0xff526457), onSecondary = Color.White,
    secondaryContainer = Color(0xffd5e8d9), onSecondaryContainer = Color(0xff222923),
    tertiary = Color(0xff705c3c), onTertiary = Color.White,
    tertiaryContainer = Color(0xfff3dfbb), onTertiaryContainer = Color(0xff3b2c15),
    background = Color(0xfffafbf8), onBackground = Color(0xff222923),
    surface = Color(0xfffafbf8), onSurface = Color(0xff222923),
    surfaceVariant = Color(0xffdfe6dc), onSurfaceVariant = Color(0xff4d584f),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff3f6ef),
    surfaceContainer = Color(0xffeef2ea), surfaceContainerHigh = Color(0xffe7ece3),
    surfaceContainerHighest = Color(0xffe0e6dc),
    outline = Color(0xff707b71), outlineVariant = Color(0xffc1cabe)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xff9ccdb0), onPrimary = Color(0xff083921),
    primaryContainer = Color(0xff244f38), onPrimaryContainer = Color(0xffccebd6),
    secondary = Color(0xffb5ccba), onSecondary = Color(0xff213528),
    secondaryContainer = Color(0xff324a3c), onSecondaryContainer = Color(0xffe2e6df),
    tertiary = Color(0xffdcc39b), onTertiary = Color(0xff3e2e16),
    tertiaryContainer = Color(0xff554329), onTertiaryContainer = Color(0xfff3dfbb),
    background = Color(0xff171b19), onBackground = Color(0xffe2e6df),
    surface = Color(0xff171b19), onSurface = Color(0xffe2e6df),
    surfaceVariant = Color(0xff414b43), onSurfaceVariant = Color(0xffc0cabf),
    surfaceContainerLowest = Color(0xff101411), surfaceContainerLow = Color(0xff1c211d),
    surfaceContainer = Color(0xff222923), surfaceContainerHigh = Color(0xff29302a),
    surfaceContainerHighest = Color(0xff343b35),
    outline = Color(0xff8b968c), outlineVariant = Color(0xff414b43)
)

@Composable
fun FluxaTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FluxaTypography,
        content = content
    )
}
