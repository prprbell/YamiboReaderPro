package org.shirakawatyu.yamibo.novel.util.manga

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.Keep
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 漫画探测工具。
 *
 * 重构后的职责：
 * 1. 使用隐藏 WebView 加载帖子页，执行站点脚本/懒加载逻辑。
 * 2. 保留 WebView 图片请求，由 CoilWebViewProxy -> MangaImagePipeline 接管。
 * 3. 通过 JS 提取整章图片 URL / title / html。
 * 4. 成功提取 URL 后，主动 handoff 整章 URL 给 MangaImagePipeline，补齐 WebView 尚未发起的图片请求。
 */
class MangaProber {

    companion object {
        private val IMAGE_EXT_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)

        /**
         * 全局缓存单例，避免频繁创建 JS bridge。
         */
        private var cachedProberJSInterface: ProberJSInterface? = null
    }

    @Keep
    class ProberJSInterface {
        var onSuccess: ((String, String) -> Unit)? = null
        var onFail: (() -> Unit)? = null

        @Keep
        @JavascriptInterface
        fun triggerSuccess(urlsJoined: String, title: String) {
            Handler(Looper.getMainLooper()).post {
                onSuccess?.invoke(urlsJoined, title)
            }
        }

        @Keep
        @JavascriptInterface
        fun triggerFail() {
            Handler(Looper.getMainLooper()).post {
                onFail?.invoke()
            }
        }
    }

    suspend fun probeUrl(
        context: Context,
        url: String,
        onSuccess: (List<String>, String, String) -> Unit,
        onFallback: () -> Unit
    ) {
        val webView = WebViewPool.acquire(context)
        val appContext = context.applicationContext

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = false
            loadsImagesAutomatically = true
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

        val failAndCleanup = {
            if (!isFinished.get()) {
                cleanupAndFinish()
                onFallback()
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
                            if (sectionName.indexOf(allowedSections[k]) !== -1) {
                                isAllowedSection = true;
                                break;
                            }
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
                    if (allImgs.length === 0) return;

                    var urls = [];
                    var seen = {};
                    for (var i = 0; i < allImgs.length; i++) {
                        var rawSrc = allImgs[i].getAttribute('zsrc') ||
                                     allImgs[i].getAttribute('data-src') ||
                                     allImgs[i].getAttribute('file') ||
                                     allImgs[i].getAttribute('src');
                        if (rawSrc) {
                            try {
                                var abs = new URL(rawSrc, document.baseURI).href;
                                if (!seen[abs]) {
                                    seen[abs] = true;
                                    urls.push(abs);
                                }
                            } catch(e) {}
                        }
                    }

                    if (urls.length > 0) {
                        if (window.ProberApi) window.ProberApi.triggerSuccess(urls.join('|||'), document.title);
                    }
                } catch(err) {}
            })();
        """.trimIndent()

        val startTime = System.currentTimeMillis()
        var absoluteMaxTimeout = 10_000L
        val maxAbsoluteTimeout = 18_000L

        try {
            val proberApi = cachedProberJSInterface ?: ProberJSInterface().also {
                cachedProberJSInterface = it
            }

            proberApi.onSuccess = { urlsJoined, title ->
                if (!isFinished.get()) {
                    webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
                        val cleanHtml = try {
                            JSON.parse(htmlResult) as? String ?: ""
                        } catch (_: Exception) {
                            htmlResult?.trim('"')
                                ?.replace("\\u003C", "<")
                                ?.replace("\\\"", "\"")
                                ?: ""
                        }

                        val urls = urlsJoined
                            .split("|||")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()

                        // 关键补齐：
                        // WebView 图片请求已经触发的部分会通过 CoilWebViewProxy 接续；
                        // 还没来得及被 WebView 请求的部分，在这里主动提交给统一管线。
                        MangaImagePipeline.handoffPrefetch(
                            context = appContext,
                            urls = urls,
                            clickedIndex = 0
                        )

                        cleanupAndFinish()
                        onSuccess(urls, title, cleanHtml)
                    }
                }
            }

            proberApi.onFail = {
                failAndCleanup()
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
                private val maxRetries = 2

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: ""
                    val accept = request?.requestHeaders?.get("Accept") ?: ""

                    val isImage = accept.contains("image/", ignoreCase = true) ||
                            urlStr.contains(IMAGE_EXT_REGEX) ||
                            urlStr.contains("attachment")

                    if (request?.isForMainFrame == false && isImage) {
                        if (!isIgnoredImageUrl(urlStr) && request.method == "GET" && urlStr.contains("yamibo.com")) {
                            val headers = mutableMapOf<String, String>()
                            request.requestHeaders?.forEach { (key, value) ->
                                headers[key] = value
                            }

                            val coilResponse = CoilWebViewProxy.interceptImage(
                                context = appContext,
                                url = urlStr,
                                headers = headers
                            )
                            if (coilResponse != null) return coilResponse

                            val proxyResponse = YamiboRetrofit.proxyWebViewResource(request)
                            if (proxyResponse != null) return proxyResponse

                            return WebResourceResponse(
                                "image/jpeg",
                                "utf-8",
                                404,
                                "Blocked by Interceptor",
                                null,
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                private fun handlePotentialTransientError(view: WebView?, errorCode: Int) {
                    if (isFinished.get()) return

                    if (transientErrors.contains(errorCode) && retryCount < maxRetries) {
                        retryCount++
                        val delayMs = retryCount * 1_500L
                        absoluteMaxTimeout = minOf(absoluteMaxTimeout + delayMs, maxAbsoluteTimeout)

                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isFinished.get()) view?.loadUrl(finalUrl)
                        }, delayMs)
                        return
                    }

                    if (errorCode == ERROR_BAD_URL || retryCount >= maxRetries) {
                        failAndCleanup()
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
                        cachedProberJSInterface?.onSuccess = null
                        cachedProberJSInterface?.onFail = null
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
                if (elapsed >= absoluteMaxTimeout) break

                delay(checkInterval)

                if (!isFinished.get()) {
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(extractJs, null)
                    }
                }
            }

            if (!isFinished.get()) {
                failAndCleanup()
            }
        } catch (e: CancellationException) {
            cleanupAndFinish()
            throw e
        } catch (_: Throwable) {
            failAndCleanup()
        }
    }

    private fun isIgnoredImageUrl(url: String): Boolean {
        return url.contains("smiley", ignoreCase = true) ||
                url.contains("avatar", ignoreCase = true) ||
                url.contains("common", ignoreCase = true) ||
                url.contains("static/image", ignoreCase = true) ||
                url.contains("template", ignoreCase = true) ||
                url.contains("block", ignoreCase = true)
    }
}
