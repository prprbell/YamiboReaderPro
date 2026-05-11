package org.shirakawatyu.yamibo.novel.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.shirakawatyu.yamibo.novel.global.GlobalData

@Composable
fun darkModeColor(light: Color, dark: Color): Color {
    val isDark by GlobalData.isDarkMode.collectAsState()
    return if (isDark) dark else light
}
