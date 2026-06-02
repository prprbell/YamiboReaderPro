package org.shirakawatyu.yamibo.novel.ui.state

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.shirakawatyu.yamibo.novel.ui.page.FullscreenApi
import org.shirakawatyu.yamibo.novel.ui.page.NativeMangaJSInterface

object BBSPageState {
    var isLoading by mutableStateOf(true)
    var showLoadError by mutableStateOf(false)
    var currentUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf("")

    // 首屏骨架屏交接状态：不移除 MainActivity 或 BBSPage 任意一方的骨架屏，
    // 只确保 MainActivity 首屏骨架在 BBSPage 内部骨架/容器接手后再退出，避免中间空白或闪烁。
    var isBbsContainerMounted by mutableStateOf(false)
        private set
    var isBbsLoadingCoverMounted by mutableStateOf(false)
        private set
    var hasMainFrameCommitted by mutableStateOf(false)
        private set
    var hasRequestedInitialLoad by mutableStateOf(false)

    val isReadyToTakeInitialSkeleton: Boolean
        get() = isBbsContainerMounted &&
                (isBbsLoadingCoverMounted || hasSuccessfullyLoaded || showLoadError)

    var lastLoginState: Boolean? = null
    var hasSuccessfullyLoaded by mutableStateOf(false)
    var fullscreenApi: FullscreenApi? = null
    var nativeMangaApi: NativeMangaJSInterface? = null
    var isErrorState by mutableStateOf(false)
    var hasExecutedInitialDelay: Boolean = false

    var isAutoRecoveringBeforeError by mutableStateOf(false)
    var autoRecoveryFailed by mutableStateOf(false)
    var autoRecoveryToken by mutableIntStateOf(0)

    fun resetForNewBbsWebView() {
        isLoading = true
        showLoadError = false
        currentUrl = null
        pageTitle = ""
        hasSuccessfullyLoaded = false
        isErrorState = false
        isAutoRecoveringBeforeError = false
        autoRecoveryFailed = false
        hasMainFrameCommitted = false
        hasRequestedInitialLoad = false
        resetInitialSkeletonHandoff()
    }

    fun resetInitialSkeletonHandoff() {
        isBbsContainerMounted = false
        isBbsLoadingCoverMounted = false
    }

    fun markBbsContainerMounted() {
        isBbsContainerMounted = true
    }

    fun markBbsLoadingCoverMounted() {
        isBbsLoadingCoverMounted = true
    }

    fun markMainFrameLoadStarted(url: String?) {
        currentUrl = url
        isLoading = true
        showLoadError = false
        isErrorState = false
        hasMainFrameCommitted = false
    }

    fun markMainFrameCommitted(url: String?, title: String?) {
        if (isUsableBbsUrl(url)) {
            currentUrl = url
        }
        pageTitle = title.orEmpty()
        hasMainFrameCommitted = true
    }

    fun markLoadSucceeded(url: String?) {
        isLoading = false
        isErrorState = false
        showLoadError = false
        finishRecoveryBeforeShowingError()
        if (isUsableBbsUrl(url)) {
            currentUrl = url
            hasSuccessfullyLoaded = true
        }
    }

    fun requestRecoveryBeforeShowingError() {
        isAutoRecoveringBeforeError = true
        autoRecoveryFailed = false
        showLoadError = false
        autoRecoveryToken++
        requestResumeRecovery()
    }

    fun finishRecoveryBeforeShowingError() {
        isAutoRecoveringBeforeError = false
        autoRecoveryFailed = false
    }

    fun failRecoveryBeforeShowingError() {
        isAutoRecoveringBeforeError = false
        autoRecoveryFailed = true
        isLoading = false
        isErrorState = true
        showLoadError = true
    }

    // 短后台：尝试恢复/刷新；长后台：直接重建 WebView，避免复用半死不活的 renderer/surface。
    private const val BACKGROUND_RECOVERY_THRESHOLD_MS = 30_000L
    private const val FORCE_RECREATE_AFTER_LONG_BACKGROUND_MS = 15 * 60 * 1000L
    private const val RESUME_RECOVERY_THROTTLE_MS = 5_000L

    var lastStoppedElapsedRealtime: Long = 0L
        private set

    var needsResumeRecovery by mutableStateOf(false)
        private set

    // Boolean 已经是 true 时再次 request 不会触发 LaunchedEffect；token 用来保证每次请求都能被消费。
    var resumeRecoveryToken by mutableIntStateOf(0)
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

    fun shouldForceRecreateWebViewAfterLongBackground(): Boolean {
        val stoppedAt = lastStoppedElapsedRealtime
        return stoppedAt > 0L &&
                SystemClock.elapsedRealtime() - stoppedAt >= FORCE_RECREATE_AFTER_LONG_BACKGROUND_MS
    }

    fun requestResumeRecovery() {
        needsResumeRecovery = true
        resumeRecoveryToken++
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

    fun isUsableBbsUrl(url: String?): Boolean {
        return !url.isNullOrBlank() &&
                url != "about:blank" &&
                !url.startsWith("data:") &&
                !url.contains("warmup=true")
    }

    fun bestRecoveryUrl(webView: WebView?, fallbackUrl: String): String {
        val viewUrl = try {
            webView?.url
        } catch (_: Throwable) {
            null
        }

        if (isErrorState || isAutoRecoveringBeforeError || autoRecoveryFailed) {
            return currentUrl?.takeIf { isUsableBbsUrl(it) }
                ?: viewUrl?.takeIf { isUsableBbsUrl(it) }
                ?: fallbackUrl
        }

        return viewUrl?.takeIf { isUsableBbsUrl(it) }
            ?: currentUrl?.takeIf { isUsableBbsUrl(it) }
            ?: fallbackUrl
    }
}
