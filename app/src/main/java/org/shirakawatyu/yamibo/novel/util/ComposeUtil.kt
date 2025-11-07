package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

class ComposeUtil {
    companion object {
        @Composable
        fun SetStatusBarColor(color: Color) {
            val context = LocalContext.current as Activity
            LaunchedEffect(color) {
                println(color)
                val lightColor: Boolean =
                    color.red * 0.299 + color.green * 0.578 + color.blue * 0.114 >= 192.0 / 255.0
                WindowCompat.getInsetsController(
                    context.window,
                    context.window.decorView
                ).isAppearanceLightStatusBars = lightColor
                context.window.statusBarColor = color.toArgb()
            }
        }
    }
}