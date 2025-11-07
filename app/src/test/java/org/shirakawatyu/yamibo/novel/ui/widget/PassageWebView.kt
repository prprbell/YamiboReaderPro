package org.shirakawatyu.yamibo.novel.ui.widget

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.PassageWebViewClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PassageWebView(url: String, onFinished: (html: String, url: String?) -> Unit) {
    val passageWebViewClient = remember { PassageWebViewClient(onFinished) }
    AndroidView(modifier = Modifier.height(0.dp), factory = { context ->
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.useWideViewPort = true
            webViewClient = passageWebViewClient
            webChromeClient = GlobalData.webChromeClient
            loadUrl(url)
        }
    })
}