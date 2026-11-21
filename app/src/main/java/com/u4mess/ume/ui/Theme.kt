package com.u4mess.ume.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Black = Color(0xFF000000)
val Elevated = Color(0xFF0D0D0D)
val Border = Color(0xFF1F1F1F)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA0A0A0)

private val UmeColors = darkColorScheme(
    primary = TextPrimary,
    onPrimary = Black,
    secondary = TextSecondary,
    onSecondary = Black,
    background = Black,
    onBackground = TextPrimary,
    surface = Elevated,
    onSurface = TextPrimary,
    surfaceVariant = Elevated,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = Border,
)

private val UmeTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

@Composable
fun UmeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UmeColors, typography = UmeTypography, content = content)
}
