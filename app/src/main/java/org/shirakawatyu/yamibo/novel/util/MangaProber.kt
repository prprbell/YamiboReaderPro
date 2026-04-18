package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
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

    companion object {
        // 新增：全局缓存单例
        private var cachedProberJSInterface: ProberJSInterface? = null
    }

    @Keep
    class ProberJSInterface {
        // 移除构造参数，改为可变属性
        var onSuccess: ((String, String) -> Unit)? = null
        var onFail: (() -> Unit)? = null

        @Keep
        @JavascriptInterface
        fun triggerSuccess(urlsJoined: String, title: String) {
            Handler(Looper.getMainLooper()).post { onSuccess?.invoke(urlsJoined, title) }
        }

        @Keep
        @JavascriptInterface
        fun triggerFail() {
            Handler(Looper.getMainLooper()).post { onFail?.invoke() }
        }
    }


    suspend fun probeUrl(
        context: Context,
        url: String,
        onSuccess: (List<String>, String, String) -> Unit,
        onFallback: () -> Unit
    ) {
        val webView = WebViewPool.acquire(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        val activity = context as? Activity
        val decorView = activity?.window?.decorView as? ViewGroup
        if (decorView != null && webView.parent == null) {
            val layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                leftMargin = -10000
                topMargin = -10000
            }
            decorView.addView(webView, layoutParams)
        }

        val isFinished = AtomicBoolean(false)
        val finalUrl = if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"

        val cleanupAndFinish = {
            if (isFinished.compareAndSet(false, true)) {
                cachedProberJSInterface?.onSuccess = null
                cachedProberJSInterface?.onFail = null

                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                WebViewPool.release(webView)
            }
        }

        val extractJs = """
            (function() {
                try {
                    if (document.readyState === 'loading') return;
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

        val startTime = System.currentTimeMillis()
        var absoluteMaxTimeout = 10000L
        val MAX_ABSOLUTE_TIMEOUT = 18000L

        try {
            val proberApi = cachedProberJSInterface ?: ProberJSInterface().also { cachedProberJSInterface = it }

            proberApi.onSuccess = { urlsJoined, title ->
                if (!isFinished.get()) {
                    webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
                        val cleanHtml = try {
                            com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
                        } catch (_: Exception) {
                            htmlResult?.trim('"')?.replace("\\u003C", "<")
                                ?.replace("\\\"", "\"") ?: ""
                        }
                        val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
                        cleanupAndFinish()
                        onSuccess(urls, title, cleanHtml)
                    }
                }
            }

            proberApi.onFail = {
                if (!isFinished.get()) {
                    cleanupAndFinish()
                    onFallback()
                }
            }

            webView.addJavascriptInterface(proberApi, "ProberApi")

            webView.webViewClient = object : YamiboWebViewClient() {
                private val transientErrors = listOf(
                    ERROR_HOST_LOOKUP,
                    ERROR_CONNECT,
                    ERROR_TIMEOUT,
                    ERROR_UNKNOWN,
                    ERROR_FAILED_SSL_HANDSHAKE
                )

                private var retryCount = 0
                private val MAX_RETRIES = 2

                private fun handlePotentialTransientError(view: WebView?, errorCode: Int) {
                    if (!isFinished.get()) {
                        if (transientErrors.contains(errorCode) && retryCount < MAX_RETRIES) {
                            retryCount++
                            val delayMs = (retryCount * 1500).toLong()

                            absoluteMaxTimeout =
                                minOf(absoluteMaxTimeout + delayMs, MAX_ABSOLUTE_TIMEOUT)

                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinished.get()) view?.loadUrl(finalUrl)
                            }, delayMs)
                            return
                        }

                        if (errorCode == ERROR_BAD_URL || retryCount >= MAX_RETRIES) {
                            cleanupAndFinish()
                            onFallback()
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)

                    val requestUrl = request?.url?.toString() ?: ""
                    if (requestUrl == "about:blank" || requestUrl.contains("warmup=true")) return

                    if (request?.isForMainFrame == true) {
                        handlePotentialTransientError(view, error?.errorCode ?: 0)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)

                    if (failingUrl == "about:blank" || failingUrl?.contains("warmup=true") == true) return
                    if (failingUrl == view?.url || failingUrl == finalUrl) {
                        handlePotentialTransientError(view, errorCode)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    if (isFinished.compareAndSet(false, true)) {
                        WebViewPool.discard(webView)
                        onFallback()
                    }
                    return true
                }
            }
            webView.resumeTimers()
            webView.loadUrl(finalUrl)

            val checkInterval = 500L

            while (!isFinished.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= absoluteMaxTimeout) {
                    break
                }

                delay(checkInterval)

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