package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 漫画探测工具
 */
class MangaProber {

    @Keep
    class ProberJSInterface(
        private val onSuccess: (String, String) -> Unit,
        private val onFail: () -> Unit
    ) {
        @Keep
        @JavascriptInterface
        fun triggerSuccess(urlsJoined: String, title: String) {
            Handler(Looper.getMainLooper()).post { onSuccess(urlsJoined, title) }
        }

        @Keep
        @JavascriptInterface
        fun triggerFail() {
            Handler(Looper.getMainLooper()).post { onFail() }
        }
    }

    suspend fun probeUrl(
        context: Context,
        url: String,
        onSuccess: (List<String>, String, String) -> Unit,
        onFallback: () -> Unit
    ) {
        val webView = WebViewPool.acquire(context)
        val isFinished = AtomicBoolean(false)
        val finalUrl = if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"

        val cleanupAndFinish = {
            if (isFinished.compareAndSet(false, true)) {
                webView.removeJavascriptInterface("ProberApi")
                webView.stopLoading()
                WebViewPool.release(webView)
            }
        }

        val extractJs = """
            javascript:(function() {
                try {
                    var sectionHeader = document.querySelector('.header h2 a');
                    var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
                    if (sectionName !== '') {
                        var allowedSections = ['中文百合漫画区', '貼圖區', '贴图区', '原创图作区', '百合漫画图源区'];
                        var isAllowedSection = false;
                        for (var k = 0; k < allowedSections.length; k++) {
                            if (sectionName.indexOf(allowedSections[k]) !== -1) { isAllowedSection = true; break; }
                        }
                        if (!isAllowedSection) {
                            if (window.ProberApi) window.ProberApi.triggerFail();
                            return;
                        }
                    }
                    
                    var typeLabel = document.querySelector('.view_tit em');
                    if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                        if (window.ProberApi) window.ProberApi.triggerFail();
                        return; 
                    }

                    var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                    if (allImgs.length === 0) return; // 没找到图片就静默退出，等待 Kotlin 下一次轮询
                    
                    var urls = [];
                    for (var i = 0; i < allImgs.length; i++) {
                        var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                        if (rawSrc) {
                            try { urls.push(new URL(rawSrc, document.baseURI).href); } catch(e) {}
                        }
                    }
                    
                    if (urls.length > 0) {
                        if (window.ProberApi) window.ProberApi.triggerSuccess(urls.join('|||'), document.title);
                    }
                } catch(err) {}
            })();
        """.trimIndent()

        try {
            webView.addJavascriptInterface(
                ProberJSInterface(
                    onSuccess = { urlsJoined, title ->
                        if (!isFinished.get()) {
                            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
                                val cleanHtml = try {
                                    com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
                                } catch (e: Exception) {
                                    htmlResult?.trim('"')?.replace("\\u003C", "<")
                                        ?.replace("\\\"", "\"") ?: ""
                                }
                                val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
                                cleanupAndFinish()
                                onSuccess(urls, title, cleanHtml)
                            }
                        }
                    },
                    onFail = {
                        if (!isFinished.get()) {
                            cleanupAndFinish()
                            onFallback()
                        }
                    }
                ), "ProberApi"
            )

            webView.webViewClient = object : YamiboWebViewClient() {

                private val fatalErrors = listOf(
                    ERROR_HOST_LOOKUP, // -2: 找不到服务器
                    ERROR_CONNECT,     // -6: 连接不到服务器
                    ERROR_BAD_URL      // -12: 错误的URL
                )

                // 旧版API
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (!isFinished.get() && failingUrl == view?.url) {
                        if (fatalErrors.contains(errorCode)) {
                            cleanupAndFinish()
                            onFallback()
                        }
                    }
                }

                // 新版API
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !isFinished.get()) {
                        val errorCode = error?.errorCode ?: 0
                        if (fatalErrors.contains(errorCode)) {
                            cleanupAndFinish()
                            onFallback()
                        }
                    }
                }
            }

            webView.loadUrl(finalUrl)

            var timeWaited = 0
            val maxWaitTime = 8000 // 探测的生命周期上限为8秒
            val checkInterval = 500
            var hasCheckedBlank = false

            while (timeWaited < maxWaitTime && !isFinished.get()) {
                delay(checkInterval.toLong())
                timeWaited += checkInterval

                if (!isFinished.get()) {
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(extractJs, null)
                    }
                }
            }

            if (!isFinished.get()) {
                cleanupAndFinish()
                onFallback()
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanupAndFinish()
            throw e
        }
    }
}