package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class ComposeUtil {
    companion object {
        @Composable
        fun SetStatusBarColor(color: Color) {
            val context = LocalContext.current as? Activity ?: return
            val view = LocalView.current
            val window = context.window
            val lightColor =
                color.red * 0.299 + color.green * 0.578 + color.blue * 0.114 >= 192.0 / 255.0

            val lifecycleOwner = LocalLifecycleOwner.current

            val applyAppearance = {
                try {
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = lightColor
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val applyFull = {
                try {
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    insetsController.isAppearanceLightStatusBars = lightColor
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            androidx.compose.runtime.SideEffect {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    applyAppearance()
                }
            }
            val scope = rememberCoroutineScope()
            DisposableEffect(lifecycleOwner, color) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        scope.launch {
                            kotlinx.coroutines.delay(100)
                            applyFull()
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

/**
 * 宿主级别的 WebView 生命周期观察者。
 * 只有当整个 App 切换到后台/前台时，才会全局暂停/恢复 JS 引擎和 WebView 渲染。
 */
@Composable
fun ActivityWebViewLifecycleObserver(webView: WebView) {
    val context = LocalContext.current
    DisposableEffect(context, webView) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    webView.pauseTimers()
                }

                else -> {}
            }
        }
        // 直接绑定宿主 Activity 的 lifecycle，而不是 Compose 局部的 NavBackStackEntry
        activity?.lifecycle?.addObserver(observer)

        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }
}