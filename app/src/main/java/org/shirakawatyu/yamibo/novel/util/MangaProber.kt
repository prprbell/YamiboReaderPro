package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.delay
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient

class MangaProber {
    class ProberJSInterface(
        private val onSuccess: (String, String, String) -> Unit,
        private val onFail: () -> Unit
    ) {
        @JavascriptInterface
        fun triggerSuccess(urlsJoined: String, title: String, htmlResult: String) {
            Handler(Looper.getMainLooper()).post { onSuccess(urlsJoined, title, htmlResult) }
        }

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
        webView.onResume()
        webView.resumeTimers()

        var isFinished = false

        val cleanupAndFinish = {
            if (!isFinished) {
                isFinished = true
                webView.removeJavascriptInterface("ProberApi")
                webView.stopLoading()
                WebViewPool.release(webView)
            }
        }
        try {
            webView.addJavascriptInterface(
                ProberJSInterface(
                    onSuccess = { urlsJoined, title, html ->
                        if (!isFinished) {
                            val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
                            cleanupAndFinish()
                            onSuccess(urls, title, html)
                        }
                    },
                    onFail = {
                        if (!isFinished) {
                            cleanupAndFinish()
                            onFallback()
                        }
                    }
                ), "ProberApi"
            )

            webView.webViewClient = object : YamiboWebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    if (finishedUrl == "about:blank") return

                    val extractJs = """
                    (function() {
                        if (window.proberStarted) return;
                        window.proberStarted = true;
                        
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

                        function extractAndOpenNative() {
                            if (!window.ProberApi) return false;
                            
                            var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                            if (allImgs.length === 0) return false;
                            
                            var urls = [];
                            for (var i = 0; i < allImgs.length; i++) {
                                var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                                if (rawSrc) {
                                    try {
                                        urls.push(new URL(rawSrc, document.baseURI).href);
                                    } catch(e) {}
                                }
                            }
                            
                            if (urls.length > 0) {
                                var html = document.documentElement.outerHTML;
                                window.ProberApi.triggerSuccess(urls.join('|||'), document.title, html);
                                return true;
                            }
                            return false;
                        }

                        if (extractAndOpenNative()) {
                            return;
                        }

                        var extractAttempts = 0;
                        var maxExtracts = 24; // 等待 6 秒，和 MangaWebPage 保持一致
                        
                        var extractTimer = setInterval(function() {
                            extractAttempts++;
                            
                            if (extractAndOpenNative()) {
                                clearInterval(extractTimer);
                                return;
                            }
                            
                            if (extractAttempts >= maxExtracts) {
                                clearInterval(extractTimer);
                                if (window.ProberApi) {
                                    window.ProberApi.triggerFail();
                                }
                            }
                        }, 250);
                    })();
                """.trimIndent()

                    view?.evaluateJavascript(extractJs, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (!isFinished) {
                        cleanupAndFinish()
                        onFallback()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !isFinished) {
                        cleanupAndFinish()
                        onFallback()
                    }
                }
            }

            val finalUrl = if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
            webView.loadUrl(finalUrl)

            delay(8000)
            if (!isFinished) {
                cleanupAndFinish()
                onFallback()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanupAndFinish()
            throw e
        }
    }
}