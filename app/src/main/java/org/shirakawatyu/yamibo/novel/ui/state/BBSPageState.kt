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

enum class BbsResumeRecoveryMode {
    None,
    HealthCheck,
    Reload
}

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
        needsResumeRecovery = false
        resumeRecoveryMode = BbsResumeRecoveryMode.None
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

    // 恢复策略和内存回收策略分离：
    // 30 秒以上统一做低成本 JS 健康检查：页面正常只补图和重注入脚本，不刷新；页面异常才 load/reload。
    // 15 分钟是省内存阈值：销毁 BBS WebView，释放 renderer/surface/图片缓存，而不是为了“修复页面”。
    private const val HEALTH_CHECK_RECOVERY_THRESHOLD_MS = 30_000L
    private const val RELEASE_WEBVIEW_FOR_MEMORY_AFTER_LONG_BACKGROUND_MS = 15 * 60 * 1000L
    private const val RESUME_RECOVERY_THROTTLE_MS = 5_000L

    var lastStoppedElapsedRealtime: Long = 0L
        private set

    var needsResumeRecovery by mutableStateOf(false)
        private set

    var resumeRecoveryMode by mutableStateOf(BbsResumeRecoveryMode.None)
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
        val mode = resumeRecoveryModeAfterBackground()
        if (mode != BbsResumeRecoveryMode.None) {
            requestResumeRecovery(mode)
        }
    }

    fun resumeRecoveryModeAfterBackground(): BbsResumeRecoveryMode {
        val stoppedAt = lastStoppedElapsedRealtime
        if (stoppedAt <= 0L) return BbsResumeRecoveryMode.None

        val elapsed = SystemClock.elapsedRealtime() - stoppedAt
        return when {
            elapsed < HEALTH_CHECK_RECOVERY_THRESHOLD_MS -> BbsResumeRecoveryMode.None
            else -> BbsResumeRecoveryMode.HealthCheck
        }
    }

    fun shouldReleaseWebViewForMemoryAfterLongBackground(): Boolean {
        val stoppedAt = lastStoppedElapsedRealtime
        return stoppedAt > 0L &&
                SystemClock.elapsedRealtime() - stoppedAt >= RELEASE_WEBVIEW_FOR_MEMORY_AFTER_LONG_BACKGROUND_MS
    }

    fun shouldForceRecreateWebViewAfterLongBackground(): Boolean {
        // 兼容 MainActivity 现有调用名；这里的“强制重建”主要是后台较久后的内存回收策略。
        return shouldReleaseWebViewForMemoryAfterLongBackground()
    }

    private fun recoveryPriority(mode: BbsResumeRecoveryMode): Int {
        return when (mode) {
            BbsResumeRecoveryMode.None -> 0
            BbsResumeRecoveryMode.HealthCheck -> 1
            BbsResumeRecoveryMode.Reload -> 2
        }
    }

    fun requestResumeRecovery(mode: BbsResumeRecoveryMode = BbsResumeRecoveryMode.HealthCheck) {
        needsResumeRecovery = true
        if (recoveryPriority(mode) >= recoveryPriority(resumeRecoveryMode)) {
            resumeRecoveryMode = mode
        }
        resumeRecoveryToken++
    }

    fun finishResumeRecovery() {
        needsResumeRecovery = false
        resumeRecoveryMode = BbsResumeRecoveryMode.None
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
