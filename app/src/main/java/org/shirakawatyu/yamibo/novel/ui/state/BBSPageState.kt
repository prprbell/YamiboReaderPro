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

/** 恢复请求的来源，用来区分普通前台恢复和“错误页展示前”的静默恢复。 */
enum class BbsRecoveryReason {
    AppResume,
    BeforeShowingError
}

/**
 * BBS 页面的有限状态机。
 *
 * 页面只能处于 Initial / Loading / Content / Recovering / Error 之一，避免原先十几个 Boolean
 * 产生互相矛盾的排列组合。
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

    private var recoveryRequest by mutableStateOf<BbsRecoveryRequest?>(null)

    var resumeRecoveryToken by mutableIntStateOf(0)
        private set

    var autoRecoveryToken by mutableIntStateOf(0)
        private set

    private var lastResumeRecoveryElapsedRealtime: Long = 0L

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

    val isAutoRecoveringBeforeError: Boolean
        get() = recoveryRequest?.reason == BbsRecoveryReason.BeforeShowingError

    val isRecovering: Boolean
        get() = recoveryRequest != null || phase is BbsPagePhase.Recovering

    val shouldDisplayLoadError: Boolean
        get() = phase is BbsPagePhase.Error && !isRecovering

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
        currentUrl = null
        pageTitle = ""
        hasMainFrameCommitted = false
        hasRequestedInitialLoad = false
        hasSuccessfullyLoaded = false
        recoveryRequest = null
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

    /** 用户主动加载、刷新或恢复时统一从这里进入 Loading。 */
    fun markLoadRequested(url: String?, clearSuccessfulContent: Boolean = false) {
        hasRequestedInitialLoad = true
        hasMainFrameCommitted = false
        if (clearSuccessfulContent) {
            hasSuccessfullyLoaded = false
        }
        if (isUsableBbsUrl(url)) {
            currentUrl = url
        }
        phase = BbsPagePhase.Loading(
            url = currentUrl ?: url,
            keepsPreviousContent = hasSuccessfullyLoaded
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
        val completedRecovery = recoveryRequest != null
        recoveryRequest = null
        if (completedRecovery) {
            lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        }
        phase = BbsPagePhase.Content(currentUrl, pageTitle)
    }

    /** 非当前主框架产生的错误，不应该把页面推进 Error。 */
    fun finishIgnoredLoadError() {
        val request = recoveryRequest
        phase = when {
            request != null -> BbsPagePhase.Recovering(
                url = currentUrl,
                mode = request.mode,
                reason = request.reason,
                keepsPreviousContent = hasSuccessfullyLoaded
            )
            hasSuccessfullyLoaded -> BbsPagePhase.Content(currentUrl, pageTitle)
            else -> BbsPagePhase.Initial
        }
    }

    /** 普通加载超时，直接显示错误页。 */
    fun showLoadErrorNow(recoveryFailed: Boolean = false) {
        recoveryRequest = null
        phase = BbsPagePhase.Error(currentUrl, recoveryFailed)
    }

    /** WebView 主框架失败后，先进入静默恢复，不立即显示错误页。 */
    fun requestRecoveryBeforeShowingError(
        mode: BbsResumeRecoveryMode = BbsResumeRecoveryMode.HealthCheck
    ) {
        hasSuccessfullyLoaded = false
        autoRecoveryToken++
        enqueueRecovery(mode, BbsRecoveryReason.BeforeShowingError)
        phase = BbsPagePhase.Recovering(
            url = currentUrl,
            mode = resumeRecoveryMode,
            reason = BbsRecoveryReason.BeforeShowingError,
            keepsPreviousContent = false
        )
    }

    /** 把已经排队的恢复明确推进 Recovering，供 HealthCheck / Reload 开始前调用。 */
    fun beginRecovery(
        mode: BbsResumeRecoveryMode = resumeRecoveryMode.takeUnless {
            it == BbsResumeRecoveryMode.None
        } ?: BbsResumeRecoveryMode.HealthCheck
    ) {
        val current = recoveryRequest
        val reason = current?.reason ?: BbsRecoveryReason.AppResume
        if (current == null) {
            enqueueRecovery(mode, reason)
        } else if (recoveryPriority(mode) > recoveryPriority(current.mode)) {
            recoveryRequest = current.copy(mode = mode)
        }
        phase = BbsPagePhase.Recovering(
            url = currentUrl,
            mode = resumeRecoveryMode,
            reason = reason,
            keepsPreviousContent = hasSuccessfullyLoaded
        )
    }

    fun failRecoveryBeforeShowingError() {
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        phase = BbsPagePhase.Error(currentUrl, recoveryFailed = true)
    }

    fun requestResumeRecovery(
        mode: BbsResumeRecoveryMode = BbsResumeRecoveryMode.HealthCheck
    ) {
        val reason = if (phase is BbsPagePhase.Error) {
            BbsRecoveryReason.BeforeShowingError
        } else {
            recoveryRequest?.reason ?: BbsRecoveryReason.AppResume
        }
        enqueueRecovery(mode, reason)

        if (phase is BbsPagePhase.Error) {
            phase = BbsPagePhase.Recovering(
                url = currentUrl,
                mode = resumeRecoveryMode,
                reason = reason,
                keepsPreviousContent = false
            )
        }
    }

    fun finishResumeRecovery() {
        recoveryRequest = null
        lastResumeRecoveryElapsedRealtime = SystemClock.elapsedRealtime()
        if (phase is BbsPagePhase.Recovering) {
            phase = if (hasSuccessfullyLoaded) {
                BbsPagePhase.Content(currentUrl, pageTitle)
            } else {
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
        const val RESUME_RECOVERY_THROTTLE_MS = 5_000L
    }
}
