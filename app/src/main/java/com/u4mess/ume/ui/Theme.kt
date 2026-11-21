package com.u4mess.ume.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.u4mess.ume.settings.ThemeMode
import com.u4mess.ume.settings.ThemeSettings

private data class UmePalette(
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
)

private val OledPalette = UmePalette(
    background = Color(0xFF000000),
    surface = Color(0xFF0E0E0E),
    border = Color(0xFF222222),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8A84),
    accent = Color(0xFF9E582B),
    onAccent = Color(0xFFFFFFFF),
)

private val DarkPalette = UmePalette(
    background = Color(0xFF0F0E0D),
    surface = Color(0xFF181716),
    border = Color(0xFF292724),
    textPrimary = Color(0xFFF5F3EF),
    textSecondary = Color(0xFF9E9890),
    accent = Color(0xFF9E582B),
    onAccent = Color(0xFFFFFFFF),
)

private val LightPalette = UmePalette(
    background = Color(0xFFF5F3EF),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE6E2DA),
    textPrimary = Color(0xFF18181A),
    textSecondary = Color(0xFF6E6A63),
    accent = Color(0xFFB86B3A),
    onAccent = Color(0xFFFFFFFF),
)

val UmeSerif = FontFamily.Serif

private val LocalUmePalette = staticCompositionLocalOf { OledPalette }
private val LocalUmeDark = staticCompositionLocalOf { true }

// Token names kept from the original OLED theme; each resolves from the active palette.
val Black: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.background

val Elevated: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.surface

val Border: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.border

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.textSecondary

val Accent: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.accent

val OnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalUmePalette.current.onAccent

val UmeDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalUmeDark.current

private val UmeTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun UmeTheme(content: @Composable () -> Unit) {
    val selected by ThemeSettings.mode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (selected) {
        ThemeMode.OLED, ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val palette = when {
        selected == ThemeMode.OLED -> OledPalette
        dark -> DarkPalette
        else -> LightPalette
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            outlineVariant = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            outlineVariant = palette.border,
        )
    }

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalUmePalette provides palette,
        LocalUmeDark provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = UmeTypography, content = content)
    }
}
