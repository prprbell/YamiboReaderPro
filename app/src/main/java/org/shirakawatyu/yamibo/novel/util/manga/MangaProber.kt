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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 漫画探测工具
 */
class MangaProber {

    companion object {
        private const val TAG = "MangaProber"
        private val IMAGE_EXT_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)
        // 启发式正则：榨取所有带 src 的 img 标签
        private val IMG_TAG_REGEX = Regex(
            """<img\s+[^>]*?(?:zsrc|data-src|file|src)=["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )

        // ─── 内存缓存 (TID -> 解析结果) ──────────────────────
        private data class CacheEntry(
            val urls: List<String>,
            val title: String,
            val messageHtml: String,
            val timestamp: Long = System.currentTimeMillis()
        )

        private const val CACHE_MAX_SIZE = 40
        private val CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(20)

        private val probeCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > CACHE_MAX_SIZE
            }
        }

        // ─── 极客优化：分段锁 (Lock Striping) ────────────────
        // 彻底杜绝使用 ConcurrentHashMap 无限增长带来的 Mutex 内存泄漏。
        // 固定 32 个锁，按 TID 的哈希值路由，O(1) 且完全 0 内存增长。
        private const val STRIPE_COUNT = 32
        private val stripedLocks = Array(STRIPE_COUNT) { Mutex() }

        // ─── JS 桥 ──────────────────────────────────────────
        private var cachedProberJSInterface: ProberJSInterface? = null

        // 规范化 URL 拼接工具 + API脏数据自愈
        private fun safeConcatUrl(base: String, path: String): String {
            val rawUrl = if (path.startsWith("http")) {
                path
            } else {
                val baseUrl = base.trimEnd('/')
                val relative = path.trimStart('/')
                "$baseUrl/$relative"
            }

            // 【极客自愈机制】专为 API 返回的裸数据擦屁股。
            // 修复 Discuz 数据库直出的残缺域 (形如 http://data/attachment/...)
            return if (rawUrl.startsWith("http://data/") || rawUrl.startsWith("https://data/")) {
                val safeBaseUrl = RequestConfig.BASE_URL.trimEnd('/')
                rawUrl.replaceFirst(Regex("^https?://data/"), "$safeBaseUrl/data/")
            } else {
                rawUrl
            }
        }
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
            // 第 1 层：快速游离态读取缓存
            val cachedEntry = getValidCache(tid)
            if (cachedEntry != null) {
                MangaImagePipeline.handoffPrefetch(appContext, cachedEntry.urls, 0)
                onSuccess(cachedEntry.urls, cachedEntry.title, cachedEntry.messageHtml)
                return
            }

            try {
                // 根据 TID 的哈希值路由到固定的分段锁
                val lockIndex = abs(tid.hashCode()) % STRIPE_COUNT
                val mutex = stripedLocks[lockIndex]

                // 加锁后，执行防并发双重检查 (Double-Checked Locking)
                val success = mutex.withLock {
                    // 第 2 层：拿到锁后再查一次缓存，如果已经被前面排队的协程请求到了，直接复用
                    val doubleCheckCache = getValidCache(tid)
                    if (doubleCheckCache != null) {
                        withContext(Dispatchers.Main) {
                            MangaImagePipeline.handoffPrefetch(appContext, doubleCheckCache.urls, 0)
                            onSuccess(doubleCheckCache.urls, doubleCheckCache.title, doubleCheckCache.messageHtml)
                        }
                        return@withLock true
                    }

                    // 确实没缓存，才去真实发 API 请求
                    fastApiProbe(appContext, tid, onSuccess)
                }

                if (success) {
                    return // 极速解析成功，直接返回，绝不启动 WebView
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "API probe failed for $tid, falling back to WebView", e)
                // 异常静默，流转到 WebView 兜底
            }
        }

        // 降级轨：兜底走原有 WebView 探针
        fallbackWebViewProbe(context, url, onSuccess, onFallback)
    }

    /**
     * 线程安全获取有效缓存
     */
    private fun getValidCache(tid: String): CacheEntry? {
        synchronized(probeCache) {
            val cached = probeCache[tid]
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                return cached
            }
            if (cached != null) {
                probeCache.remove(tid) // 淘汰过期缓存
            }
            return null
        }
    }

    /**
     * 高速 API 嗅探逻辑：直接请求 JSON 并正则提取图片链接，且写入缓存
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

        val threadObj = variables.getJSONObject("thread") ?: return@withContext false
        val title = threadObj.getString("subject") ?: return@withContext false
        val threadAuthorId = threadObj.getString("authorid") ?: ""

        // --- 深度还原 WebView 的业务过滤逻辑 ---
        val forumName = variables.getJSONObject("forum")?.getString("name") ?: ""
        if (forumName.isNotEmpty()) {
            val allowedSections = listOf("中文百合漫画区", "貼圖區", "贴图区", "原创图作区", "百合漫画图源区")
            if (allowedSections.none { forumName.contains(it) }) {
                return@withContext false
            }
        }

        val typeName = threadObj.getString("typename") ?: ""
        if (typeName.contains("公告") || title.contains("公告")) {
            return@withContext false
        }
        // --- 结束业务过滤 ---

        val postList = variables.getJSONArray("postlist") ?: return@withContext false
        if (postList.isEmpty()) return@withContext false

        val urls = mutableListOf<String>()
        val seenFromMessage = mutableSetOf<String>()
        val combinedMessage = StringBuilder()

        // 极客优化：不仅看 1 楼，还要遍历整个首页。
        // 但严格校验 `authorid` 必须是楼主，解决楼主跨多层楼连载漫画的漏图问题，同时过滤水友回复。
        for (i in 0 until postList.size) {
            val post = postList.getJSONObject(i) ?: continue
            val postAuthorId = post.getString("authorid") ?: ""

            // 跳过非楼主的楼层（允许一楼楼主匿名或 ID 异常的极罕见情况，所以 i==0 默认放行）
            if (i != 0 && postAuthorId != threadAuthorId) continue

            val message = post.getString("message") ?: ""
            combinedMessage.append(message).append("<br/>")

            // 1. 从正文中提取 img 标签
            val matches = IMG_TAG_REGEX.findAll(message)
            for (match in matches) {
                val rawUrl = match.groupValues[1]
                if (!isIgnoredImageUrl(rawUrl)) {
                    val fullUrl = safeConcatUrl(RequestConfig.BASE_URL, rawUrl)
                    if (seenFromMessage.add(fullUrl)) {
                        urls.add(fullUrl)
                    }
                }
            }

            // 2. 补全未插入正文的纯附件 (Discuz 经典坑点)
            val attachments = post.getJSONObject("attachments")
            if (attachments != null) {
                for (key in attachments.keys) {
                    val attachObj = attachments.getJSONObject(key) ?: continue
                    val urlPrefix = attachObj.getString("url") ?: ""
                    val attachmentPath = attachObj.getString("attachment") ?: ""

                    if (urlPrefix.isNotEmpty() && attachmentPath.isNotEmpty()) {
                        val fullUrl = safeConcatUrl(
                            if (urlPrefix.startsWith("http")) urlPrefix else "${RequestConfig.BASE_URL}/$urlPrefix",
                            attachmentPath
                        )
                        if (!seenFromMessage.contains(fullUrl) && !isIgnoredImageUrl(fullUrl)) {
                            urls.add(fullUrl)
                        }
                    }
                }
            }
        }

        if (urls.isNotEmpty()) {
            val urlList = urls.toList()

            // 将纯正文拼接并套一层 <div class="message">，欺骗 MangaHtmlParser 提取全楼层的同页目录
            val compatibleHtml = "<div class=\"message\">$combinedMessage</div>"

            // 安全写入缓存
            synchronized(probeCache) {
                probeCache[tid] = CacheEntry(urlList, title, compatibleHtml)
            }

            withContext(Dispatchers.Main) {
                MangaImagePipeline.handoffPrefetch(
                    context = context,
                    urls = urlList,
                    clickedIndex = 0
                )
                onSuccess(urlList, title, compatibleHtml)
            }
            return@withContext true
        }
        return@withContext false
    }

    /**
     * 传统的无头 WebView 降级兜底方案
     * (底层兼容逻辑未受重构影响，保证 100% 可用性兜底)
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
        val finalUrl = safeConcatUrl(RequestConfig.BASE_URL, url)

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