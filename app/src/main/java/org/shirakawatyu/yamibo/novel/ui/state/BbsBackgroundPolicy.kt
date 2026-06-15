package org.shirakawatyu.yamibo.novel.ui.state

import android.os.SystemClock

data class BbsBackgroundDecision(
    val recoveryMode: BbsResumeRecoveryMode,
    val shouldReleaseWebViewForMemory: Boolean
)

/**
 * 只负责后台时长判定，不接触 Compose 状态和 WebView。
 *
 * 重要：30 秒以上只触发 Silent HealthCheck，不代表页面不健康，也不代表要显示骨架屏。
 * 主动释放 WebView 的阈值改为极端长后台，避免"每次回来都像重新加载"。
 */
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
            recoveryMode = if (elapsed >= SILENT_REPAIR_THRESHOLD_MS) {
                BbsResumeRecoveryMode.HealthCheck
            } else {
                BbsResumeRecoveryMode.None
            },
            shouldReleaseWebViewForMemory = elapsed >= RELEASE_WEBVIEW_FOR_MEMORY_AFTER_EXTREME_BACKGROUND_MS
        )
    }

    private companion object {
        const val SILENT_REPAIR_THRESHOLD_MS = 30_000L
        const val RELEASE_WEBVIEW_FOR_MEMORY_AFTER_EXTREME_BACKGROUND_MS = 6 * 60 * 60 * 1000L
    }
}
