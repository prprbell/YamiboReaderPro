package org.shirakawatyu.yamibo.novel.ui.state

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.shirakawatyu.yamibo.novel.ui.page.FullscreenApi
import org.shirakawatyu.yamibo.novel.ui.page.NativeMangaJSInterface

object BBSPageState {
    var isLoading by mutableStateOf(true)
    var showLoadError by mutableStateOf(false)
    var currentUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf("")

    var lastLoginState: Boolean? = null
    var hasSuccessfullyLoaded: Boolean = false
    var fullscreenApi: FullscreenApi? = null
    var nativeMangaApi: NativeMangaJSInterface? = null
    var isErrorState: Boolean = false
    var hasExecutedInitialDelay: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var pauseRunnable: Runnable? = null

    fun schedulePause(webView: WebView, delayMs: Long = 8000L) {
        cancelPause()
        pauseRunnable = Runnable {
            try {
                webView.onPause()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        handler.postDelayed(pauseRunnable!!, delayMs)
    }

    fun cancelPause() {
        pauseRunnable?.let { handler.removeCallbacks(it) }
        pauseRunnable = null
    }
}