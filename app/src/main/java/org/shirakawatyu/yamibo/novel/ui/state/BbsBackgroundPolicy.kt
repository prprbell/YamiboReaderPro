package org.shirakawatyu.yamibo.novel.ui.state

import android.os.SystemClock

data class BbsBackgroundDecision(
    val recoveryMode: BbsResumeRecoveryMode,
    val shouldReleaseWebViewForMemory: Boolean
)

/** 只负责后台时长判定，不接触 Compose 状态和 WebView。 */
class BbsBackgroundPolicy(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private var stoppedAtElapsedRealtime: Long = 0L

    fun markStopped() {
        stoppedAtElapsedRealtime = elapsedRealtime()
    }

    /**
     * 消费一次后台记录，避免同一次 onStop 被重复处理。
     * 调用方应先根据 decision 处理 WebView，再等待下一次 markStopped。
     */
    fun consumeStartDecision(): BbsBackgroundDecision {
        val stoppedAt = stoppedAtElapsedRealtime
        stoppedAtElapsedRealtime = 0L
        if (stoppedAt <= 0L) {
            return BbsBackgroundDecision(
                recoveryMode = BbsResumeRecoveryMode.None,
                shouldReleaseWebViewForMemory = false
            )
        }

        val elapsed = elapsedRealtime() - stoppedAt
        return BbsBackgroundDecision(
            recoveryMode = if (elapsed >= HEALTH_CHECK_RECOVERY_THRESHOLD_MS) {
                BbsResumeRecoveryMode.HealthCheck
            } else {
                BbsResumeRecoveryMode.None
            },
            shouldReleaseWebViewForMemory = elapsed >= RELEASE_WEBVIEW_FOR_MEMORY_AFTER_LONG_BACKGROUND_MS
        )
    }

    private companion object {
        const val HEALTH_CHECK_RECOVERY_THRESHOLD_MS = 30_000L
        const val RELEASE_WEBVIEW_FOR_MEMORY_AFTER_LONG_BACKGROUND_MS = 15 * 60 * 1000L
    }
}
