package org.shirakawatyu.yamibo.novel.ui.widget

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import org.shirakawatyu.yamibo.novel.global.GlobalData

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ComposableWebView(url: String) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.useWideViewPort = true
            webViewClient = GlobalData.webViewClient
            webChromeClient = GlobalData.webChromeClient
            loadUrl(url)
        }
    })
}

