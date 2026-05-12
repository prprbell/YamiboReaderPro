package org.shirakawatyu.yamibo.novel.ui.state

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    var hasSuccessfullyLoaded by mutableStateOf(false)
    var fullscreenApi: FullscreenApi? = null
    var nativeMangaApi: NativeMangaJSInterface? = null
    var isErrorState by mutableStateOf(false)
    var hasExecutedInitialDelay: Boolean = false

    // App 级前后台恢复标记。使用 elapsedRealtime，避免用户手动改时间/系统校时影响判断。
    private const val BACKGROUND_RECOVERY_THRESHOLD_MS = 30_000L
    private const val RESUME_RECOVERY_THROTTLE_MS = 5_000L

    var lastStoppedElapsedRealtime: Long = 0L
        private set
    var needsResumeRecovery by mutableStateOf(false)
        private set
    private var lastResumeRecoveryElapsedRealtime: Long = 0L

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

    fun markAppStopped() {
        lastStoppedElapsedRealtime = SystemClock.elapsedRealtime()
    }

    fun markAppStarted() {
        val stoppedAt = lastStoppedElapsedRealtime
        if (stoppedAt > 0L &&
            SystemClock.elapsedRealtime() - stoppedAt >= BACKGROUND_RECOVERY_THRESHOLD_MS
        ) {
            requestResumeRecovery()
        }
    }

    fun requestResumeRecovery() {
        needsResumeRecovery = true
    }

    fun finishResumeRecovery() {
        needsResumeRecovery = false
        lastStoppedElapsedRealtime = 0L
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
    }

    fun resumeRecoveryThrottleDelayMs(): Long {
        val last = lastResumeRecoveryElapsedRealtime
        if (last <= 0L) return 0L

        val elapsed = SystemClock.elapsedRealtime() - last
        return (RESUME_RECOVERY_THROTTLE_MS - elapsed).coerceAtLeast(0L)
    }

    fun isResumeRecoveryThrottled(): Boolean {
        return resumeRecoveryThrottleDelayMs() > 0L
    }

    fun isUsableBbsUrl(url: String?): Boolean {
        return !url.isNullOrBlank() &&
                url != "about:blank" &&
                !url.startsWith("data:") &&
                !url.contains("warmup=true")
    }

    fun bestRecoveryUrl(webView: WebView?, fallbackUrl: String): String {
        return webView?.url?.takeIf { isUsableBbsUrl(it) }
            ?: currentUrl?.takeIf { isUsableBbsUrl(it) }
            ?: fallbackUrl
    }
}