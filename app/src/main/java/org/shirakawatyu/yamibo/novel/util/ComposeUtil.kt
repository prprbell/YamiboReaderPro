package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class ComposeUtil {
    companion object {
        @Composable
        fun SetStatusBarColor(color: Color) {
            val context = LocalContext.current as Activity
            val view = LocalView.current
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner, color) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        try {
                            val window = context.window
                            val insetsController = WindowCompat.getInsetsController(window, view)

                            val lightColor: Boolean =
                                color.red * 0.299 + color.green * 0.578 + color.blue * 0.114 >= 192.0 / 255.0

                            insetsController.isAppearanceLightStatusBars = lightColor

                            window.statusBarColor = color.toArgb()

                            WindowCompat.setDecorFitsSystemWindows(window, true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                lifecycleOwner.lifecycle.addObserver(observer)

                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
        }
    }
}