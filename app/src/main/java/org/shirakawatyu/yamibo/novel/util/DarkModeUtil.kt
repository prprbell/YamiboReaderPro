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
         * 纯白 · Pure White
         * 现代极简风。岩石灰状态栏作为冷色识别带，
         * 极淡冷灰主体背景把卡片"抬"出阅读层，纯白卡片承接内容，
         * 浅冷灰分区头柔和分隔，炭灰作为主色用于按钮/链接/FAB。
         *
         * 层次：statusBar(岩石灰) → background(极淡冷灰) → surfaceVariant(浅冷灰) → surface(纯白)
         * 与原生论坛（暖米黄）、论坛蓝（冷蓝）、苔色（冷绿）三个色相完全分开，
         * 形成 中性灰阶 这一独立维度。主色 #1F2937 为整体引入足够对比，
         * 强调可点击元素而不引入额外色彩噪声。
         */
        val PURE_WHITE = LightThemeColors(
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
         * 论坛蓝 · Cobalt Forum
         * 致敬 Discuz 论坛源生 #2B7ACD 蓝色基因。
         * 深钴蓝状态栏 = 经典论坛 header 的现代化，
         * 冷调浅灰蓝主体，纯白卡片层级最分明，
         * 论坛原色 #2B7ACD 留作主色 — 链接/按钮/FAB 与历史一致。
         *
         * 层次：statusBar(深钴蓝) → background(浅灰蓝) → surfaceVariant(淡蓝) → surface(纯白)
         * 这是三个主题中对比度最高的设计，适合长文阅读与扫读列表。
         */
        val COBALT_FORUM = LightThemeColors(
            statusBar = Color(0xFF1F5A8F),       // 深钴蓝顶部识别带
            navBar = Color(0xFFFFFFFF),          // 纯白底栏
            background = Color(0xFFEDF1F6),      // 冷调浅灰蓝背景
            surface = Color(0xFFFFFFFF),         // 纯白卡片（最大对比）
            surfaceVariant = Color(0xFFDDE7F1),  // 淡蓝分区头
            primary = Color(0xFF2B7ACD),         // 论坛原色蓝，作为主色保留
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1A2733),    // 深石板灰文字
            onSurface = Color(0xFF1A2733),
            onSurfaceVariant = Color(0xFF5B6A7A),// 中调石板次要文字
            outline = Color(0xFFCFDAE5),         // 冷边框
            tertiary = Color(0xFFDDE7F1),        // 淡蓝药丸 = 底部导航选中指示器（与 surfaceVariant 同色）
            onSecondary = Color(0xFF1A2733),
        )

        /**
         * 苔色 · Sage Garden
         * 自然森林意境。深苔绿状态栏沉稳压住顶部，
         * 浅绿主体淡雅护眼，近白卡片浮起，
         * 中调森林绿作为主色 — 比蓝主题低饱和但同样清晰。
         *
         * 层次：statusBar(深苔绿) → background(浅绿) → surfaceVariant(淡苔) → surface(近白)
         * 是三个主题中视觉刺激最低的，适合夜晚弱光环境下的长时间使用。
         */
        val SAGE_GARDEN = LightThemeColors(
            statusBar = Color(0xFF37553F),       // 深苔绿顶部识别带
            navBar = Color(0xFFFAFCF9),          // 近白底栏
            background = Color(0xFFE9F0EA),      // 浅绿主体背景
            surface = Color(0xFFFAFCF9),         // 近白卡片
            surfaceVariant = Color(0xFFD3E0D5),  // 淡苔分区头
            primary = Color(0xFF4F7857),         // 中调森林绿主色
            onPrimary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1F2B22),    // 深森林文字
            onSurface = Color(0xFF1F2B22),
            onSurfaceVariant = Color(0xFF5F6E5E),// 苔灰次要文字
            outline = Color(0xFFC5D2C2),         // 软绿边框
            tertiary = Color(0xFFD3E0D5),        // 淡苔药丸 = 底部导航选中指示器（与 surfaceVariant 同色）
            onSecondary = Color(0xFF1F2B22),
        )

        fun forTheme(themeId: Int) = when (themeId) {
            2 -> COBALT_FORUM
            3 -> SAGE_GARDEN
            else -> PURE_WHITE
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