package org.shirakawatyu.yamibo.novel.ui.state

import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WebView 暂停调度器。由 Activity 持有并在 onDestroy 时 close，不进入页面状态机。
 */
class BbsWebViewPauseScheduler(
    private val scope: CoroutineScope
) {
    private var pauseJob: Job? = null
    private var hasScheduledInitialPause = false

    fun schedule(webView: WebView) {
        cancel()
        val delayMs = if (hasScheduledInitialPause) SUBSEQUENT_DELAY_MS else INITIAL_DELAY_MS
        hasScheduledInitialPause = true

        pauseJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            runCatching { webView.onPause() }
            pauseJob = null
        }
    }

    fun cancel() {
        pauseJob?.cancel()
        pauseJob = null
    }

    fun close() {
        cancel()
    }

    private companion object {
        const val INITIAL_DELAY_MS = 8_000L
        const val SUBSEQUENT_DELAY_MS = 3_000L
    }
}
