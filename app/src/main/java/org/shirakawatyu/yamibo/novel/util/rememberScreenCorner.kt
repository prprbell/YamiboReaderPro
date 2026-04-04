package org.shirakawatyu.yamibo.novel.util

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/**
 * 获取当前设备屏幕的物理圆角半径 (Android 12+)
 */
@Composable
fun rememberScreenCorner(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var cornerRadius by remember { mutableStateOf(0.dp) }

    LaunchedEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.post {
                val insets = view.rootWindowInsets
                val topLeftCorner = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                val radiusPx = topLeftCorner?.radius ?: 0
                val rawDp = with(density) { radiusPx.toDp() }
                cornerRadius = (rawDp - 8.dp).coerceIn(0.dp, 24.dp)
            }
        }
    }

    return cornerRadius
}