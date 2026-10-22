package com.fluxa.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun sans(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp
)

val FluxaTypography = Typography(
    displayLarge = sans(48, 56, FontWeight.SemiBold),
    displayMedium = sans(40, 48, FontWeight.SemiBold),
    displaySmall = sans(36, 44, FontWeight.SemiBold),
    headlineLarge = sans(32, 40, FontWeight.SemiBold),
    headlineMedium = sans(28, 36, FontWeight.SemiBold),
    headlineSmall = sans(24, 32, FontWeight.SemiBold),
    titleLarge = sans(22, 30, FontWeight.SemiBold),
    titleMedium = sans(17, 26, FontWeight.SemiBold),
    titleSmall = sans(14, 20, FontWeight.Medium),
    bodyLarge = sans(18, 28),
    bodyMedium = sans(16, 24),
    bodySmall = sans(13, 20),
    labelLarge = sans(14, 20, FontWeight.Medium),
    labelMedium = sans(12, 18, FontWeight.Medium),
    labelSmall = sans(11, 16, FontWeight.Medium)
)
