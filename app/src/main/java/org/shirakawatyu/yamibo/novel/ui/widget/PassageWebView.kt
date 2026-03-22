package org.shirakawatyu.yamibo.novel.ui.widget

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import org.shirakawatyu.yamibo.novel.util.WebViewPool

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PassageWebView(
    url: String,
    loadImages: Boolean,
    onFinished: (
        success: Boolean,
        html: String,
        url: String?,
        maxPage: Int,
        title: String?
    ) -> Unit
) {
    val passageWebViewClient = remember { PassageWebViewClient(onFinished) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .offset(x = (-10000).dp),
        factory = { context ->
            WebViewPool.acquire(context).apply {
                // 根据当前页面需求动态配置图片拦截逻辑
                settings.loadsImagesAutomatically = loadImages
                settings.blockNetworkImage = !loadImages

                // 挂载业务 Client
                webViewClient = passageWebViewClient
                webChromeClient = GlobalData.webChromeClient
            }
        },
        update = { webView ->
            webView.onResume()
            webView.resumeTimers()

            if (url != lastLoadedUrl) {
                webView.loadUrl(url)
                lastLoadedUrl = url
            } else {
                webView.settings.loadsImagesAutomatically = loadImages
                webView.settings.blockNetworkImage = !loadImages
            }
        },
        onRelease = { webView ->
            // 2. 离开页面时，归还给对象池进行清洗和保留
            WebViewPool.release(webView)
        }
    )
}