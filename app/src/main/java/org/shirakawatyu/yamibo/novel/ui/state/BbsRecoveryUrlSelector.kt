package org.shirakawatyu.yamibo.novel.ui.state

import android.webkit.WebView

/** Stateless URL selection policy used by resume/error recovery. */
fun selectBbsRecoveryUrl(
    pageState: BBSPageState,
    webView: WebView?,
    fallbackUrl: String
): String {
    val viewUrl = runCatching { webView?.url }.getOrNull()

    return if (pageState.isErrorState || pageState.isAutoRecoveringBeforeError) {
        pageState.currentUrl?.takeIf(pageState::isUsableBbsUrl)
            ?: viewUrl?.takeIf(pageState::isUsableBbsUrl)
            ?: fallbackUrl
    } else {
        viewUrl?.takeIf(pageState::isUsableBbsUrl)
            ?: pageState.currentUrl?.takeIf(pageState::isUsableBbsUrl)
            ?: fallbackUrl
    }
}
