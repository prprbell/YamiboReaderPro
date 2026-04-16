package org.shirakawatyu.yamibo.novel.ui.widget

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.PassageWebViewClient
import org.shirakawatyu.yamibo.novel.util.WebViewPool

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PassageWebView(
    url: String,
    loadImages: Boolean,
    modifier: Modifier = Modifier,
    onFinished: (
        success: Boolean,
        html: String,
        url: String?,
        maxPage: Int,
        title: String?
    ) -> Unit
) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    val context = LocalContext.current

    val passageWebViewClient = remember {
        PassageWebViewClient(context) { success, html, loadedUrl, maxPage, title ->
            currentOnFinished(success, html, loadedUrl, maxPage, title)
        }
    }

    val lastLoadedUrlRef = remember { arrayOfNulls<String>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebViewPool.acquire(context).apply {
                onResume()
                resumeTimers()

                // 根据当前页面需求动态配置图片拦截逻辑
                settings.loadsImagesAutomatically = loadImages
                settings.blockNetworkImage = !loadImages

                // 挂载业务Client
                webViewClient = passageWebViewClient
                webChromeClient = GlobalData.webChromeClient
            }
        },
        update = { webView ->
            webView.settings.loadsImagesAutomatically = loadImages
            webView.settings.blockNetworkImage = !loadImages

            if (url != lastLoadedUrlRef[0]) {
                webView.loadUrl(url)
                lastLoadedUrlRef[0] = url
            }
        },
        onRelease = { webView ->
            // 离开页面时，归还给对象池进行清洗和保留
            WebViewPool.release(webView)
        }
    )
}