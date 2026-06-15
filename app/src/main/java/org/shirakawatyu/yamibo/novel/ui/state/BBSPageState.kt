package org.shirakawatyu.yamibo.novel.ui.state

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 恢复强度。Reload 的优先级高于 HealthCheck。 */
enum class BbsResumeRecoveryMode {
    None,
    HealthCheck,
    Reload
}

/** 恢复请求的来源，用来区分普通前台恢复和"错误页展示前"的静默恢复。 */
enum class BbsRecoveryReason {
    AppResume,
    BeforeShowingError
}

/**
 * WebView 表面可信度。
 *
 * 这层只服务 UI：
 * - TrustedVisible：可信，直接显示 WebView。
 * - RepairingVisible：正在静默修复/抽检，但上一帧可信，继续显示 WebView。
 * - UntrustedBlocking：没有可信内容或已经确认不可恢复，必须用骨架屏接管。
 */
enum class BbsSurfaceTrust {
    TrustedVisible,
    RepairingVisible,
    UntrustedBlocking
}

/** 最近一次"确定可见且健康"的页面快照。 */
data class BbsLastKnownGoodSnapshot(
    val url: String?,
    val title: String,
    val elapsedRealtime: Long,
    val hadMainFrameCommitted: Boolean
)

/**
 * BBS 页面的有限状态机。
 *
 * 注意：HealthCheck 不再等同于 Recovering。
 * 30 秒后台恢复只进入 RepairingVisible 静默自愈；只有确定不可恢复时才进入 Recovering/UntrustedBlocking。
 */
sealed interface BbsPagePhase {
    data object Initial : BbsPagePhase

    data class Loading(
        val url: String?,
        val keepsPreviousContent: Boolean
    ) : BbsPagePhase

    data class Content(
        val url: String?,
        val title: String
    ) : BbsPagePhase

    data class Recovering(
        val url: String?,
        val mode: BbsResumeRecoveryMode,
        val reason: BbsRecoveryReason,
        val keepsPreviousContent: Boolean
    ) : BbsPagePhase

    data class Error(
        val url: String?,
        val recoveryFailed: Boolean
    ) : BbsPagePhase
}

data class BbsRecoveryRequest(
    val mode: BbsResumeRecoveryMode,
    val reason: BbsRecoveryReason,
    val token: Int
)

/**
 * Activity 级实例，由 MainActivity 创建和销毁。
 *
 * 该类不持有 Context、Handler、WebView 或 JS bridge，生命周期完全跟随宿主 Activity。
 */
class BBSPageState(
    val skeletonHandoff: BbsSkeletonHandoffState = BbsSkeletonHandoffState()
) {
    var phase by mutableStateOf<BbsPagePhase>(BbsPagePhase.Initial)
        private set

    var surfaceTrust by mutableStateOf(BbsSurfaceTrust.UntrustedBlocking)
        private set

    var currentUrl by mutableStateOf<String?>(null)
        private set

    var pageTitle by mutableStateOf("")
        private set

    var hasMainFrameCommitted by mutableStateOf(false)
        private set

    var hasRequestedInitialLoad by mutableStateOf(false)
        private set

    var hasSuccessfullyLoaded by mutableStateOf(false)
        private set

    var lastLoginState: Boolean? = null
        private set

    var lastKnownGoodSnapshot by mutableStateOf<BbsLastKnownGoodSnapshot?>(null)
        private set

    private var recoveryRequest by mutableStateOf<BbsRecoveryRequest?>(null)

    var resumeRecoveryToken by mutableIntStateOf(0)
        private set

    var autoRecoveryToken by mutableIntStateOf(0)
        private set

    private var lastResumeRecoveryElapsedRealtime: Long = 0L
    private var wasLoadingWhenBackgrounded: Boolean = false
    private var consecutiveSilentProbeTimeouts: Int = 0

    val isLoading: Boolean
        get() = phase is BbsPagePhase.Loading || phase is BbsPagePhase.Recovering

    val isErrorState: Boolean
        get() = phase is BbsPagePhase.Error

    val showLoadError: Boolean
        get() = phase is BbsPagePhase.Error

    val autoRecoveryFailed: Boolean
        get() = (phase as? BbsPagePhase.Error)?.recoveryFailed == true

    val needsResumeRecovery: Boolean
        get() = recoveryRequest != null

    val resumeRecoveryMode: BbsResumeRecoveryMode
        get() = recoveryRequest?.mode ?: BbsResumeRecoveryMode.None

    val resumeRecoveryReason: BbsRecoveryReason
        get() = recoveryRequest?.reason ?: BbsRecoveryReason.AppResume

    val isAutoRecoveringBeforeError: Boolean
        get() = recoveryRequest?.reason == BbsRecoveryReason.BeforeShowingError

    val isSilentResumeRepairing: Boolean
        get() = surfaceTrust == BbsSurfaceTrust.RepairingVisible

    val isBlockingRecovery: Boolean
        get() = surfaceTrust == BbsSurfaceTrust.UntrustedBlocking &&
                (phase is BbsPagePhase.Loading || phase is BbsPagePhase.Recovering || phase is BbsPagePhase.Initial)

    val isRecovering: Boolean
        get() = recoveryRequest != null || phase is BbsPagePhase.Recovering

    val shouldDisplayLoadError: Boolean
        get() = phase is BbsPagePhase.Error && !isRecovering

    val shouldShowBbsLoadingCover: Boolean
        get() = surfaceTrust == BbsSurfaceTrust.UntrustedBlocking &&
                !shouldDisplayLoadError &&
                (isLoading || !hasSuccessfullyLoaded || phase is BbsPagePhase.Initial)

    val isBbsContainerMounted: Boolean
        get() = skeletonHandoff.isContainerMounted

    val isBbsLoadingCoverMounted: Boolean
        get() = skeletonHandoff.isLoadingCoverMounted

    val isReadyToTakeInitialSkeleton: Boolean
        get() = skeletonHandoff.isReady(
            hasSuccessfullyLoaded = hasSuccessfullyLoaded,
            shouldDisplayLoadError = shouldDisplayLoadError
        )

    fun resetForNewBbsWebView() {
        phase = BbsPagePhase.Initial
        surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
        currentUrl = null
        pageTitle = ""
        hasMainFrameCommitted = false
        hasRequestedInitialLoad = false
        hasSuccessfullyLoaded = false
        lastKnownGoodSnapshot = null
        recoveryRequest = null
        wasLoadingWhenBackgrounded = false
        consecutiveSilentProbeTimeouts = 0
        skeletonHandoff.reset()
    }

    fun resetInitialSkeletonHandoff() {
        skeletonHandoff.reset()
    }

    fun markBbsContainerMounted() {
        skeletonHandoff.markContainerMounted()
    }

    fun markBbsLoadingCoverMounted() {
        skeletonHandoff.markLoadingCoverMounted()
    }

    /** Activity onStop 时记录离开前是否处于半加载。半加载页面回来后不能直接信任。 */
    fun markAppBackgrounded(url: String?, title: String?, progress: Int) {
        updatePageSnapshot(url, title)
        wasLoadingWhenBackgrounded = isLoading || (progress in 1..99) || !hasMainFrameCommitted
    }

    /** 用户主动加载、刷新或恢复时统一从这里进入 Loading。 */
    fun markLoadRequested(
        url: String?,
        clearSuccessfulContent: Boolean = false,
        forceBlockingSurface: Boolean = false
    ) {
        hasRequestedInitialLoad = true
        hasMainFrameCommitted = false
        if (clearSuccessfulContent) {
            hasSuccessfullyLoaded = false
            lastKnownGoodSnapshot = null
        }
        if (isUsableBbsUrl(url)) {
            currentUrl = url
        }
        val canKeepPreviousContent = hasSuccessfullyLoaded && !clearSuccessfulContent && !forceBlockingSurface
        surfaceTrust = if (canKeepPreviousContent) {
            BbsSurfaceTrust.TrustedVisible
        } else {
            BbsSurfaceTrust.UntrustedBlocking
        }
        phase = BbsPagePhase.Loading(
            url = currentUrl ?: url,
            keepsPreviousContent = canKeepPreviousContent
        )
    }

    fun markBlockingLoadRequested(url: String?, clearSuccessfulContent: Boolean = true) {
        markLoadRequested(
            url = url,
            clearSuccessfulContent = clearSuccessfulContent,
            forceBlockingSurface = true
        )
    }

    fun markMainFrameLoadStarted(url: String?) {
        markLoadRequested(url = url, clearSuccessfulContent = false)
    }

    fun markMainFrameCommitted(url: String?, title: String?) {
        if (isUsableBbsUrl(url)) {
            currentUrl = url
        }
        pageTitle = title.orEmpty()
        hasMainFrameCommitted = true
    }

    fun updatePageSnapshot(url: String?, title: String?) {
        if (isUsableBbsUrl(url)) {
            currentUrl = url
        }
        pageTitle = title.orEmpty()
    }

    fun updateLoginState(isLoggedIn: Boolean) {
        lastLoginState = isLoggedIn
    }

    fun markLoadSucceeded(url: String?) {
        if (isUsableBbsUrl(url)) {
            currentUrl = url
            hasSuccessfullyLoaded = true
        }
        recoveryRequest = null
        wasLoadingWhenBackgrounded = false
        consecutiveSilentProbeTimeouts = 0
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        surfaceTrust = BbsSurfaceTrust.TrustedVisible
        lastKnownGoodSnapshot = BbsLastKnownGoodSnapshot(
            url = currentUrl,
            title = pageTitle,
            elapsedRealtime = SystemClock.elapsedRealtime(),
            hadMainFrameCommitted = hasMainFrameCommitted
        )
        phase = BbsPagePhase.Content(currentUrl, pageTitle)
    }

    /** 非当前主框架产生的错误，不应该把页面推进 Error。 */
    fun finishIgnoredLoadError() {
        val request = recoveryRequest
        phase = when {
            request != null && surfaceTrust == BbsSurfaceTrust.UntrustedBlocking -> BbsPagePhase.Recovering(
                url = currentUrl,
                mode = request.mode,
                reason = request.reason,
                keepsPreviousContent = false
            )
            hasSuccessfullyLoaded -> BbsPagePhase.Content(currentUrl, pageTitle)
            else -> BbsPagePhase.Initial
        }
    }

    /** 普通加载超时，直接显示错误页。 */
    fun showLoadErrorNow(recoveryFailed: Boolean = false) {
        recoveryRequest = null
        surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
        phase = BbsPagePhase.Error(currentUrl, recoveryFailed)
    }

    /** WebView 主框架失败后，先进入骨架屏接管，再自动恢复。 */
    fun requestRecoveryBeforeShowingError(
        mode: BbsResumeRecoveryMode = BbsResumeRecoveryMode.HealthCheck
    ) {
        hasSuccessfullyLoaded = false
        lastKnownGoodSnapshot = null
        surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
        autoRecoveryToken++
        enqueueRecovery(mode, BbsRecoveryReason.BeforeShowingError)
        phase = BbsPagePhase.Recovering(
            url = currentUrl,
            mode = resumeRecoveryMode,
            reason = BbsRecoveryReason.BeforeShowingError,
            keepsPreviousContent = false
        )
    }

    /**
     * 普通 AppResume 只排队静默修复，不改变 UI 到骨架屏。
     * 如果当前已经是错误页，则必须 blocking recovery。
     */
    fun requestResumeRecovery(
        mode: BbsResumeRecoveryMode = BbsResumeRecoveryMode.HealthCheck
    ) {
        val reason = if (phase is BbsPagePhase.Error) {
            BbsRecoveryReason.BeforeShowingError
        } else {
            recoveryRequest?.reason ?: BbsRecoveryReason.AppResume
        }
        enqueueRecovery(mode, reason)

        if (reason == BbsRecoveryReason.BeforeShowingError || phase is BbsPagePhase.Error) {
            beginBlockingRecovery(mode = mode, reason = reason, clearSuccessfulContent = true)
        }
    }

    /** 30 秒以上后台恢复：上一帧可信时，只进入静默修复态，继续显示 WebView。 */
    fun beginSilentResumeRepair() {
        if (!canTrustLastVisibleSurface(currentUrl)) {
            beginBlockingRecovery(
                mode = resumeRecoveryMode.takeUnless { it == BbsResumeRecoveryMode.None }
                    ?: BbsResumeRecoveryMode.HealthCheck,
                reason = resumeRecoveryReason,
                clearSuccessfulContent = true
            )
            return
        }
        surfaceTrust = BbsSurfaceTrust.RepairingVisible
        if (phase !is BbsPagePhase.Content) {
            phase = BbsPagePhase.Content(currentUrl, pageTitle)
        }
    }

    fun finishSilentResumeRepair(healthy: Boolean) {
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        if (healthy) {
            wasLoadingWhenBackgrounded = false
            consecutiveSilentProbeTimeouts = 0
            surfaceTrust = BbsSurfaceTrust.TrustedVisible
            hasSuccessfullyLoaded = true
            lastKnownGoodSnapshot = BbsLastKnownGoodSnapshot(
                url = currentUrl,
                title = pageTitle,
                elapsedRealtime = SystemClock.elapsedRealtime(),
                hadMainFrameCommitted = hasMainFrameCommitted
            )
            phase = BbsPagePhase.Content(currentUrl, pageTitle)
        } else if (hasSuccessfullyLoaded) {
            surfaceTrust = BbsSurfaceTrust.TrustedVisible
            phase = BbsPagePhase.Content(currentUrl, pageTitle)
        }
    }

    /** 返回 true 表示连续超时过多，需要升级为骨架屏接管。 */
    fun noteSilentProbeTimeout(): Boolean {
        consecutiveSilentProbeTimeouts++
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        surfaceTrust = if (hasSuccessfullyLoaded) {
            BbsSurfaceTrust.TrustedVisible
        } else {
            BbsSurfaceTrust.UntrustedBlocking
        }
        return consecutiveSilentProbeTimeouts >= MAX_SILENT_PROBE_TIMEOUTS_BEFORE_BLOCKING
    }

    /** 把恢复明确推进 Recovering，供真正需要 load/reload 的场景调用。 */
    fun beginRecovery(
        mode: BbsResumeRecoveryMode = resumeRecoveryMode.takeUnless {
            it == BbsResumeRecoveryMode.None
        } ?: BbsResumeRecoveryMode.HealthCheck
    ) {
        beginBlockingRecovery(mode = mode, reason = resumeRecoveryReason, clearSuccessfulContent = true)
    }

    fun beginBlockingRecovery(
        mode: BbsResumeRecoveryMode = resumeRecoveryMode.takeUnless {
            it == BbsResumeRecoveryMode.None
        } ?: BbsResumeRecoveryMode.HealthCheck,
        reason: BbsRecoveryReason = resumeRecoveryReason,
        clearSuccessfulContent: Boolean = true
    ) {
        val current = recoveryRequest
        if (current == null) {
            enqueueRecovery(mode, reason)
        } else if (recoveryPriority(mode) > recoveryPriority(current.mode)) {
            recoveryRequest = current.copy(mode = mode)
        }
        if (clearSuccessfulContent) {
            hasSuccessfullyLoaded = false
            lastKnownGoodSnapshot = null
        }
        surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
        phase = BbsPagePhase.Recovering(
            url = currentUrl,
            mode = resumeRecoveryMode,
            reason = reason,
            keepsPreviousContent = false
        )
    }

    fun failRecoveryBeforeShowingError() {
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
        phase = BbsPagePhase.Error(currentUrl, recoveryFailed = true)
    }

    fun finishResumeRecovery() {
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        if (phase is BbsPagePhase.Recovering) {
            phase = if (hasSuccessfullyLoaded) {
                surfaceTrust = BbsSurfaceTrust.TrustedVisible
                BbsPagePhase.Content(currentUrl, pageTitle)
            } else {
                surfaceTrust = BbsSurfaceTrust.UntrustedBlocking
                BbsPagePhase.Initial
            }
        }
    }

    fun resumeRecoveryThrottleDelayMs(): Long {
        val last = lastResumeRecoveryElapsedRealtime
        if (last <= 0L) return 0L

        val elapsed = SystemClock.elapsedRealtime() - last
        return (RESUME_RECOVERY_THROTTLE_MS - elapsed).coerceAtLeast(0L)
    }

    /** Native 快速门控：只有这些条件都满足时，恢复第一帧才允许直接显示旧 WebView。 */
    fun canTrustLastVisibleSurface(webViewUrl: String?): Boolean {
        if (!hasSuccessfullyLoaded) return false
        if (!hasMainFrameCommitted) return false
        if (wasLoadingWhenBackgrounded) return false
        if (isErrorState || showLoadError) return false
        if (!isUsableBbsUrl(webViewUrl ?: currentUrl)) return false
        if (lastKnownGoodSnapshot == null) return false
        return true
    }

    fun isUsableBbsUrl(url: String?): Boolean {
        return !url.isNullOrBlank() &&
                url != "about:blank" &&
                !url.startsWith("data:") &&
                !url.contains("warmup=true")
    }

    private fun enqueueRecovery(mode: BbsResumeRecoveryMode, reason: BbsRecoveryReason) {
        val current = recoveryRequest
        val selectedMode = if (recoveryPriority(mode) >= recoveryPriority(current?.mode)) {
            mode
        } else {
            current?.mode ?: mode
        }
        val selectedReason = if (
            current?.reason == BbsRecoveryReason.BeforeShowingError ||
            reason == BbsRecoveryReason.BeforeShowingError
        ) {
            BbsRecoveryReason.BeforeShowingError
        } else {
            BbsRecoveryReason.AppResume
        }

        resumeRecoveryToken++
        recoveryRequest = BbsRecoveryRequest(
            mode = selectedMode,
            reason = selectedReason,
            token = resumeRecoveryToken
        )
    }

    private fun recoveryPriority(mode: BbsResumeRecoveryMode?): Int {
        return when (mode) {
            null, BbsResumeRecoveryMode.None -> 0
            BbsResumeRecoveryMode.HealthCheck -> 1
            BbsResumeRecoveryMode.Reload -> 2
        }
    }

    private companion object {
        const val RESUME_RECOVERY_THROTTLE_MS = 2_000L
        const val MAX_SILENT_PROBE_TIMEOUTS_BEFORE_BLOCKING = 2
    }
}
