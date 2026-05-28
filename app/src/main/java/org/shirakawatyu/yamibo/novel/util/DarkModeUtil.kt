package org.shirakawatyu.yamibo.novel.util

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.shirakawatyu.yamibo.novel.global.GlobalData

interface ThemeColors {
    val statusBar: Color
    val navBar: Color
    val background: Color
    val surface: Color
    val surfaceVariant: Color
    val primary: Color
    val onPrimary: Color
    val onBackground: Color
    val onSurface: Color
    val onSurfaceVariant: Color
    val outline: Color
    val tertiary: Color
    val onSecondary: Color
}

data class DarkThemeColors(
    override val statusBar: Color,
    override val navBar: Color,
    override val background: Color,
    override val surface: Color,
    override val surfaceVariant: Color,
    override val primary: Color,
    override val onPrimary: Color,
    override val onBackground: Color,
    override val onSurface: Color,
    override val onSurfaceVariant: Color,
    override val outline: Color,
    override val tertiary: Color,
    override val onSecondary: Color,
) : ThemeColors {
    fun toDarkColorScheme() = darkColorScheme(
        primary = primary,
        primaryContainer = surfaceVariant,
        secondary = surface,
        secondaryContainer = surfaceVariant,
        tertiary = tertiary,
        background = background,
        surface = surface,
        onPrimary = onPrimary,
        onPrimaryContainer = onSecondary,
        onSecondary = onSecondary,
        onSecondaryContainer = onSecondary,
        onTertiary = onSecondary,
        onBackground = onBackground,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
    )

    companion object {
        val CLASSIC = DarkThemeColors(
            statusBar = Color(0xFF1A1A1A),
            navBar = Color(0xFF1A1A1A),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            surfaceVariant = Color(0xFF2A2A2A),
            primary = Color(0xFFCC7755),
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFFCCCCCC),
            onSurface = Color(0xFFCCCCCC),
            onSurfaceVariant = Color(0xFF999999),
            outline = Color(0xFF444444),
            tertiary = Color(0xFF2A2A2A),
            onSecondary = Color(0xFFCCCCCC),
        )

        val OKLCH = DarkThemeColors(
            statusBar = Color(0xFF13191F),
            navBar = Color(0xFF13191F),
            background = Color(0xFF0F1419),
            surface = Color(0xFF191F26),
            surfaceVariant = Color(0xFF20262E),
            primary = Color(0xFFCC7755),
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFFBCC0C6),
            onSurface = Color(0xFFBCC0C6),
            onSurfaceVariant = Color(0xFF8A8F95),
            outline = Color(0xFF495058),
            tertiary = Color(0xFF191F26),
            onSecondary = Color(0xFFBCC0C6),
        )

        val OLED = DarkThemeColors(
            statusBar = Color(0xFF000000),
            navBar = Color(0xFF080808),
            background = Color(0xFF000000),
            surface = Color(0xFF121212),
            surfaceVariant = Color(0xFF1A1A1A),
            primary = Color(0xFFCC7755),
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFFB3B3B3),
            onSurface = Color(0xFFB3B3B3),
            onSurfaceVariant = Color(0xFF808080),
            outline = Color(0xFF2A2A2A),
            tertiary = Color(0xFF121212),
            onSecondary = Color(0xFFB3B3B3),
        )

        val TWILIGHT = DarkThemeColors(
            statusBar = Color(0xFF191925),
            navBar = Color(0xFF191925),
            background = Color(0xFF15151F),
            surface = Color(0xFF1E1E2E),
            surfaceVariant = Color(0xFF252535),
            primary = Color(0xFFB983C5),
            onPrimary = Color(0xFFFAF5FF),
            onBackground = Color(0xFFBEBCC4),
            onSurface = Color(0xFFBEBCC4),
            onSurfaceVariant = Color(0xFF89868F),
            outline = Color(0xFF3E394C),
            tertiary = Color(0xFF1E1E2E),
            onSecondary = Color(0xFFBEBCC4),
        )

        fun forTheme(themeId: Int) = when (themeId) {
            1 -> OKLCH
            2 -> OLED
            3 -> TWILIGHT
            else -> CLASSIC
        }
    }
}

data class LightThemeColors(
    override val statusBar: Color,
    override val navBar: Color,
    override val background: Color,
    override val surface: Color,
    override val surfaceVariant: Color,
    override val primary: Color,
    override val onPrimary: Color,
    override val onBackground: Color,
    override val onSurface: Color,
    override val onSurfaceVariant: Color,
    override val outline: Color,
    override val tertiary: Color,
    override val onSecondary: Color,
) : ThemeColors {
    fun toLightColorScheme() = lightColorScheme(
        primary = primary,
        primaryContainer = surfaceVariant,
        secondary = surface,
        secondaryContainer = surfaceVariant,
        tertiary = tertiary,
        background = background,
        surface = surface,
        onPrimary = onPrimary,
        onPrimaryContainer = onSecondary,
        onSecondary = onSecondary,
        onSecondaryContainer = onSecondary,
        onTertiary = onSecondary,
        onBackground = onBackground,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
    )

    companion object {
        val MODERN_WHITE = LightThemeColors(
            statusBar = Color(0xFF64748B),
            navBar = Color(0xFF64748B),
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEF2F6),
            primary = Color(0xFF64748B),
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF111827),
            onSurface = Color(0xFF111827),
            onSurfaceVariant = Color(0xFF4B5563),
            outline = Color(0xFFE2E8F0),
            tertiary = Color(0xFFEEF2F6),
            onSecondary = Color(0xFF111827),
        )

        fun forTheme(themeId: Int) = when (themeId) {
            else -> MODERN_WHITE
        }
    }
}

@Composable
fun currentDarkThemeColors(): DarkThemeColors? {
    val isDark by GlobalData.isDarkMode.collectAsState()
    val themeId by GlobalData.darkModeTheme.collectAsState()
    return if (isDark) DarkThemeColors.forTheme(themeId) else null
}

@Composable
fun currentLightThemeColors(): LightThemeColors? {
    val isDark by GlobalData.isDarkMode.collectAsState()
    val themeId by GlobalData.lightModeTheme.collectAsState()
    return if (!isDark && themeId > 0) LightThemeColors.forTheme(themeId) else null
}

@Composable
fun darkThemeColor(light: Color, pick: ThemeColors.() -> Color): Color {
    val darkTheme = currentDarkThemeColors()
    val lightTheme = currentLightThemeColors()
    return when {
        darkTheme != null -> pick(darkTheme)
        lightTheme != null -> pick(lightTheme)
        else -> light
    }
}

@Composable
fun darkModeColor(light: Color, dark: Color): Color {
    val isDark by GlobalData.isDarkMode.collectAsState()
    return if (isDark) dark else light
}
