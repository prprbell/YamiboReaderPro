package org.shirakawatyu.yamibo.novel.util

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.shirakawatyu.yamibo.novel.global.GlobalData

/** 主题色板通用接口，DarkThemeColors 和 LightThemeColors 字段名完全一致 */
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

/** 单个夜间主题的完整色板 */
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

/** 单个日间主题的完整色板 */
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
        /**
         * Slate · 岩板
         * 中性灰阶主题。岩石灰状态栏作为冷色识别带，
         * 极淡冷灰主体背景把卡片"抬"出阅读层，纯白卡片承接内容，
         * 浅冷灰分区头柔和分隔，炭灰作为主色用于按钮/链接/FAB。
         *
         * 层次：statusBar(岩石灰) → background(极淡冷灰) → surfaceVariant(浅冷灰) → surface(纯白)
         */
        val SLATE = LightThemeColors(
            statusBar = Color(0xFF1F2937),       // 岩石灰顶部识别带（slate-800）
            navBar = Color(0xFFFFFFFF),          // 纯白底栏，与卡片同色
            background = Color(0xFFF7F8FA),      // 极淡冷灰主体背景
            surface = Color(0xFFFFFFFF),         // 纯白卡片
            surfaceVariant = Color(0xFFEFF1F4),  // 浅冷灰分区头
            primary = Color(0xFF1F2937),         // 岩石灰主色（slate-800），中性高对比
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF111827),    // gray-900 主文字
            onSurface = Color(0xFF111827),
            onSurfaceVariant = Color(0xFF6B7280),// gray-500 次要文字
            outline = Color(0xFFE5E7EB),         // gray-200 边框
            tertiary = Color(0xFFEFF1F4),        // 浅冷灰药丸 = 底部导航选中指示器（与 surfaceVariant 同色）
            onSecondary = Color(0xFF111827),
        )

        /**
         * 纯白 · White
         * 百度贴吧移动端风格。白 header、淡紫灰 #F7F7FA 背景、
         * 纯白卡片、近黑文字 #141414。极简中性，无明显品牌色。
         *
         * 层次：statusBar(白) → background(淡紫灰) → surfaceVariant(浅灰) → surface(纯白)
         */
        val WHITE = LightThemeColors(
            statusBar = Color(0xFFFFFFFF),         // 纯白顶部识别带
            navBar = Color(0xFFFFFFFF),            // 纯白底栏
            background = Color(0xFFF7F7FA),        // 淡紫灰主体背景
            surface = Color(0xFFFFFFFF),           // 纯白卡片
            surfaceVariant = Color(0xFFF3F2F5),    // 浅灰分区头
            primary = Color(0xFF515154),           // 中灰主色 — FAB/按钮
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF141414),      // 近黑主文字
            onSurface = Color(0xFF141414),
            onSurfaceVariant = Color(0xFF515154),  // 中灰次要文字
            outline = Color(0xFFF3F2F5),           // 浅灰边框
            tertiary = Color(0xFFF3F2F5),          // 浅灰药丸 = 底部导航选中指示器（与 surfaceVariant 同色）
            onSecondary = Color(0xFF141414),
        )

        fun forTheme(themeId: Int) = when (themeId) {
            2 -> WHITE
            else -> SLATE
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

/** 根据当前日间主题返回对应的色板；若为夜间模式或使用默认日间返回 null */
@Composable
fun currentLightThemeColors(): LightThemeColors? {
    val isDark by GlobalData.isDarkMode.collectAsState()
    val themeId by GlobalData.lightModeTheme.collectAsState()
    return if (!isDark && themeId > 0) LightThemeColors.forTheme(themeId) else null
}

/** 返回当前主题下的颜色：日间默认用 light，夜间/日间主题分别取对应色板 */
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

/** 原有的双色切换（向后兼容，不走多主题色板） */
@Composable
fun darkModeColor(light: Color, dark: Color): Color {
    val isDark by GlobalData.isDarkMode.collectAsState()
    return if (isDark) dark else light
}