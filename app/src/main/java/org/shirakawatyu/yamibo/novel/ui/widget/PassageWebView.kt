package org.shirakawatyu.yamibo.novel.ui.widget

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.PassageWebViewClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PassageWebView(
    url: String,
    loadImages: Boolean,
    onFinished: (success: Boolean, html: String, url: String?, maxPage: Int) -> Unit
) {
    val passageWebViewClient = remember { PassageWebViewClient(onFinished) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = Modifier.height(0.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.TRANSPARENT)

                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                if (loadImages) {
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                } else {
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true
                }
                webViewClient = passageWebViewClient
                webChromeClient = GlobalData.webChromeClient

            }
        },
        update = { webView ->

            webView.onResume()
            webView.resumeTimers()

            // 只有当VM请求的URL (url) 与上次请求加载的URL (lastLoadedUrl)
            // 不同时，才调用 loadUrl。
            // 这可以防止中断由JS点击触发的内部重定向。
            // 否则，可能会卡在加载页面
            if (url != lastLoadedUrl) {
                webView.loadUrl(url)
                lastLoadedUrl = url // 记住刚刚请求加载的URL
            }
        },
        onRelease = { webView ->
            webView.apply {
                onPause()
                stopLoading()
                webViewClient = WebViewClient()
                webChromeClient = null
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
        }
    )
}