package org.shirakawatyu.yamibo.novel.util

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.shirakawatyu.yamibo.novel.global.GlobalData

/** 单个夜间主题的完整色板 */
data class DarkThemeColors(
    val statusBar: Color,
    val navBar: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val tertiary: Color,
    val onSecondary: Color,
) {
    fun toDarkColorScheme() = darkColorScheme(
        primary = primary,
        secondary = surface,
        tertiary = tertiary,
        background = background,
        surface = surface,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onTertiary = onSecondary,
        onBackground = onBackground,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
    )

    companion object {
        val CLASSIC = DarkThemeColors(
            statusBar = Color(0xFF1a1a1a),
            navBar = Color(0xFF1a1a1a),
            background = Color(0xFF121212),
            surface = Color(0xFF1e1e1e),
            surfaceVariant = Color(0xFF2a2a2a),
            primary = Color(0xFFcc7755),
            onPrimary = Color(0xFFffffff),
            onBackground = Color(0xFFcccccc),
            onSurface = Color(0xFFcccccc),
            onSurfaceVariant = Color(0xFF999999),
            outline = Color(0xFF444444),
            tertiary = Color(0xFF2a2a2a),
            onSecondary = Color(0xFFcccccc),
        )

        val OKLCH = DarkThemeColors(
            statusBar = Color(0xFF13191F),
            navBar = Color(0xFF13191F),
            background = Color(0xFF0F1419),
            surface = Color(0xFF191F26),
            surfaceVariant = Color(0xFF20262E),
            primary = Color(0xFFcc7755),
            onPrimary = Color(0xFFffffff),
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
            surfaceVariant = Color(0xFF1a1a1a),
            primary = Color(0xFFcc7755),
            onPrimary = Color(0xFFffffff),
            onBackground = Color(0xFFB3B3B3),
            onSurface = Color(0xFFB3B3B3),
            onSurfaceVariant = Color(0xFF808080),
            outline = Color(0xFF2a2a2a),
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
            onPrimary = Color(0xFFfaf5ff),
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

/** 根据当前夜间主题返回对应的色板；若未开启夜间模式返回 null */
@Composable
fun currentDarkThemeColors(): DarkThemeColors? {
    val isDark by GlobalData.isDarkMode.collectAsState()
    val themeId by GlobalData.darkModeTheme.collectAsState()
    return if (isDark) DarkThemeColors.forTheme(themeId) else null
}

/** 返回当前主题下的颜色：日间模式用 light，夜间模式根据当前主题选 */
@Composable
fun darkThemeColor(light: Color, pick: DarkThemeColors.() -> Color): Color {
    val theme = currentDarkThemeColors()
    return if (theme != null) pick(theme) else light
}

/** 原有的双色切换（向后兼容，不走多主题色板） */
@Composable
fun darkModeColor(light: Color, dark: Color): Color {
    val isDark by GlobalData.isDarkMode.collectAsState()
    return if (isDark) dark else light
}
