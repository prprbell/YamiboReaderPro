package org.shirakawatyu.yamibo.novel.util.manga

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 漫画探测工具 (极速重构版)
 * 采用 API 正则扫描 + WebView 降级兜底双轨机制。
 */
class MangaProber {

    companion object {
        private const val TAG = "MangaProber"
        private val IMAGE_EXT_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)
        private val IMG_TAG_REGEX = Regex("""<img\s+[^>]*?(?:zsrc|data-src|file|src)=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)

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
        val tid = MangaTitleCleaner.extractTidFromUrl(url)
        val appContext = context.applicationContext

        if (tid != null) {
            try {
                val success = fastApiProbe(appContext, tid, onSuccess)
                if (success) {
                    Log.i(TAG, "Fast API Probe Success for TID: $tid")
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "API Probe failed for $tid, falling back to WebView", e)
            }
        }

        fallbackWebViewProbe(context, url, onSuccess, onFallback)
    }

    /**
     * API嗅探
     */
    private suspend fun fastApiProbe(
        context: Context,
        tid: String,
        onSuccess: (List<String>, String, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mangaApi = YamiboRetrofit.getInstance().create(MangaApi::class.java)
        val jsonStr = mangaApi.getThreadDetailApi(tid).string()

        val root = JSON.parseObject(jsonStr)
        val variables = root.getJSONObject("Variables") ?: return@withContext false

        // 校验板块等合法性
        val title = variables.getJSONObject("thread")?.getString("subject") ?: return@withContext false
        val postList = variables.getJSONArray("postlist") ?: return@withContext false

        if (postList.isEmpty()) return@withContext false
        val firstPost = postList.getJSONObject(0)
        val message = firstPost.getString("message") ?: return@withContext false

        val urls = mutableListOf<String>()

        // 1. 从正文中提取 img 标签
        val matches = IMG_TAG_REGEX.findAll(message)
        for (match in matches) {
            val rawUrl = match.groupValues[1]
            if (!isIgnoredImageUrl(rawUrl)) {
                urls.add(if (rawUrl.startsWith("http")) rawUrl else "${RequestConfig.BASE_URL}/$rawUrl")
            }
        }

        // 2. 补全未插入正文的纯附件
        val attachments = firstPost.getJSONObject("attachments")
        if (attachments != null) {
            for (key in attachments.keys) {
                val attachObj = attachments.getJSONObject(key)
                if (attachObj != null) {
                    val urlPrefix = attachObj.getString("url") ?: ""
                    val attachmentPath = attachObj.getString("attachment") ?: ""
                    if (urlPrefix.isNotEmpty() && attachmentPath.isNotEmpty()) {
                        val fullUrl = if (urlPrefix.startsWith("http")) "$urlPrefix$attachmentPath" else "${RequestConfig.BASE_URL}/$urlPrefix$attachmentPath"
                        if (!urls.contains(fullUrl) && !isIgnoredImageUrl(fullUrl)) {
                            urls.add(fullUrl)
                        }
                    }
                }
            }
        }

        if (urls.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                // 将提取到的 URLs 送入统一图片预加载管线
                MangaImagePipeline.handoffPrefetch(
                    context = context,
                    urls = urls,
                    clickedIndex = 0
                )
                // 传递 message 充当临时 HTML 以供上层兼容目录生成逻辑
                onSuccess(urls, title, message)
            }
            return@withContext true
        }
        return@withContext false
    }

    /**
     * WebView兜底方案
     */
    private suspend fun fallbackWebViewProbe(
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