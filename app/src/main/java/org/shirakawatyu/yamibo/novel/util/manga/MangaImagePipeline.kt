package org.shirakawatyu.yamibo.novel.util.manga

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 漫画图片统一加载管线。
 *
 * 核心语义：
 * 1. 保留 WebView -> Coil -> Native 的接续能力。
 * 2. WebView 接管、进入 Native 前 handoff、Native 热窗口预取、跨章节冷预取统一入口。
 * 3. 所有后台下载共享同一个 in-flight map，避免 WebView / Native / 章节预取重复调度同一 URL。
 * 4. 统一 Cookie / Referer / diskCacheKey / memoryCacheKey / force reload 语义。
 */
@OptIn(ExperimentalCoilApi::class)
object MangaImagePipeline {
    private const val TAG = "MangaImagePipeline"
    private const val REFERER = "https://bbs.yamibo.com/"
    private const val WEBVIEW_WAIT_TIMEOUT_MS = 60_000L

    enum class Source {
        WEBVIEW_TAKEOVER,
        HANDOFF_HOT,
        HANDOFF_COLD,
        NATIVE_HOT_WINDOW,
        NATIVE_WARM_WINDOW,
        NATIVE_COLD_WINDOW,
        CHAPTER_COLD_PREFETCH
    }

    enum class LoadResult {
        MEMORY_HIT,
        DISK_HIT,
        NETWORK_SUCCESS,
        NETWORK_FAILED,
        CANCELLED
    }

    private data class InFlightLoad(
        val cacheKey: String,
        val url: String,
        val source: Source,
        val deferred: Deferred<LoadResult>,
        val owners: MutableSet<String>
    )

    private data class PrefetchSpec(
        val url: String,
        val source: Source,
        val memory: Boolean,
        val rank: Int
    )

    private val pipelineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    private val inFlight = ConcurrentHashMap<String, InFlightLoad>()
    private val ownerUrlKeys = ConcurrentHashMap<String, MutableSet<String>>()

    /** WebView takeover / handoff 首屏热图 / Native 当前附近热图。 */
    private val highSemaphore = Semaphore(4)

    /** Native 中距离窗口预热。 */
    private val warmSemaphore = Semaphore(3)

    /** 远距离预取、跨章节整章冷预取，只进磁盘，低并发。 */
    private val coldSemaphore = Semaphore(2)

    /**
     * WebView 图片请求接管入口。
     *
     * 保留原业务语义：
     * - disk cache hit：立即返回 WebResourceResponse。
     * - miss：由 Coil 下载到 disk cache；下载完成后打开 snapshot 返回给 WebView。
     * - 失败或超时：返回 null，由调用方 fallback 到 YamiboRetrofit.proxyWebViewResource。
     */
    fun interceptForWebView(
        context: Context,
        url: String,
        headers: Map<String, String>? = null
    ): WebResourceResponse? {
        val appContext = context.applicationContext
        val diskCache = appContext.imageLoader.diskCache ?: return null
        val key = cacheKey(url)

        openSnapshot(diskCache, key)?.let { snapshot ->
            return createResponse(url, snapshot)
        }

        val ownerKey = "webview:$key"
        val deferred = ensureDiskCached(
            context = appContext,
            url = url,
            headers = headers.orEmpty(),
            ownerKey = ownerKey,
            source = Source.WEBVIEW_TAKEOVER,
            memory = false
        )

        return try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(WEBVIEW_WAIT_TIMEOUT_MS) {
                    deferred.await()
                }
                openSnapshot(diskCache, key)?.let { snapshot -> createResponse(url, snapshot) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WebView takeover failed: $url", t)
            null
        }
    }

    /**
     * BBSPage / MinePage / MangaWebPage / MangaProber 已经拿到整章图片 URL 后调用。
     *
     * 这一步用于补齐“WebView 还没来得及发起资源请求”的图片，保证进入 Native 后也能继续接续。
     */
    fun handoffPrefetch(
        context: Context,
        urls: List<String>,
        clickedIndex: Int = 0,
        headers: Map<String, String> = emptyMap(),
        cookie: String = ""
    ) {
        val normalized = urls.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) return

        val safeIndex = clickedIndex.coerceIn(0, normalized.lastIndex)
        val ordered = buildList {
            addAll(normalized.drop(safeIndex))
            addAll(normalized.take(safeIndex).asReversed())
        }.distinct()

        val ownerKey = "handoff:${ordered.firstOrNull().orEmpty().hashCode()}:${System.nanoTime()}"

        ordered.forEachIndexed { index, imgUrl ->
            val hot = index <= 2
            ensureDiskCached(
                context = context.applicationContext,
                url = imgUrl,
                headers = headers,
                ownerKey = ownerKey,
                source = if (hot) Source.HANDOFF_HOT else Source.HANDOFF_COLD,
                memory = hot,
                explicitCookie = cookie
            )
        }
    }

    /**
     * Native 阅读页当前窗口预热入口。
     *
     * NativeMangaPage 只需要在 currentIndex / flatPages / readMode 变化后调用这个方法；
     * 具体哪些图进 memory、哪些只进 disk，由这里统一决定。
     */
    fun updateNativeWindow(
        context: Context,
        ownerKey: String,
        urls: List<String>,
        currentIndex: Int,
        cookie: String = "",
        isVerticalMode: Boolean = true,
        isRtl: Boolean = false
    ) {
        if (urls.isEmpty() || currentIndex !in urls.indices) {
            cancelOwner(ownerKey)
            return
        }

        val specs = buildNativeWindowSpecs(
            urls = urls,
            currentIndex = currentIndex,
            isVerticalMode = isVerticalMode,
            isRtl = isRtl
        )

        val desiredKeys = specs.map { cacheKey(it.url) }.toMutableSet()
        val previousKeys = ownerUrlKeys[ownerKey].orEmpty().toSet()

        previousKeys
            .filterNot { it in desiredKeys }
            .forEach { releaseOwnerFromUrl(ownerKey, it) }

        ownerUrlKeys[ownerKey] = desiredKeys

        specs.sortedBy { it.rank }.forEach { spec ->
            ensureDiskCached(
                context = context.applicationContext,
                url = spec.url,
                ownerKey = ownerKey,
                source = spec.source,
                memory = spec.memory,
                explicitCookie = cookie
            )
        }
    }

    /**
     * 跨章节整章冷预取。
     *
     * 只进磁盘，不污染内存缓存。用于替代 MangaReaderManager 里的直接 imageLoader.enqueue。
     */
    fun coldPrefetchChapter(
        context: Context,
        urls: List<String>,
        cookie: String = ""
    ) {
        val normalized = urls.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) return

        val ownerKey = "chapter:${normalized.firstOrNull().orEmpty().hashCode()}:${System.nanoTime()}"
        normalized.forEach { imgUrl ->
            ensureDiskCached(
                context = context.applicationContext,
                url = imgUrl,
                ownerKey = ownerKey,
                source = Source.CHAPTER_COLD_PREFETCH,
                memory = false,
                explicitCookie = cookie
            )
        }
    }

    /**
     * 生成 Native 可见图片请求。
     *
     * forceVersion > 0 时，data URL 增加 _t 参数绕过旧响应；
     * 但 diskCacheKey / memoryCacheKey 仍使用原始 URL，避免产生一堆 _t 缓存副本。
     */
    fun buildImageRequest(
        context: Context,
        url: String,
        cookie: String = "",
        forceVersion: Int = 0,
        memory: Boolean = true
    ): ImageRequest {
        return newImageRequestBuilder(
            context = context,
            url = url,
            cookie = cookie,
            forceVersion = forceVersion,
            memory = memory
        ).build()
    }

    /**
     * 给需要 listener 的 UI 使用：
     * MangaImagePipeline.newImageRequestBuilder(...).listener(...).build()
     */
    fun newImageRequestBuilder(
        context: Context,
        url: String,
        cookie: String = "",
        forceVersion: Int = 0,
        memory: Boolean = true
    ): ImageRequest.Builder {
        val dataUrl = cacheBustingUrl(url, forceVersion)
        val key = cacheKey(url)

        return ImageRequest.Builder(context.applicationContext)
            .data(dataUrl)
            .diskCacheKey(key)
            .memoryCacheKey(MemoryCache.Key(key))
            .crossfade(false)
            .applyDefaultHeaders(url = url, explicitCookie = cookie, headers = emptyMap())
            .apply {
                if (forceVersion > 0) {
                    // 强刷时：跳过旧缓存读取，但把新结果写回同一个原始 key。
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                } else {
                    memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                }
            }
    }

    suspend fun evict(context: Context, url: String) = withContext(Dispatchers.IO) {
        val key = cacheKey(url)
        try {
            context.applicationContext.imageLoader.diskCache?.remove(key)
            context.applicationContext.imageLoader.memoryCache?.remove(MemoryCache.Key(key))
        } catch (t: Throwable) {
            Log.w(TAG, "Evict failed: $url", t)
        }
    }

    fun isCached(
        context: Context,
        url: String,
        includeMemory: Boolean = true
    ): Boolean {
        val appContext = context.applicationContext
        val key = cacheKey(url)

        if (includeMemory && appContext.imageLoader.memoryCache?.get(MemoryCache.Key(key)) != null) {
            return true
        }

        val diskCache = appContext.imageLoader.diskCache ?: return false
        return openSnapshot(diskCache, key)?.use { true } == true
    }

    fun cancelOwner(ownerKey: String) {
        val keys = ownerUrlKeys.remove(ownerKey)?.toSet().orEmpty()
        keys.forEach { key -> releaseOwnerFromUrl(ownerKey, key) }
    }

    /**
     * 只取消某个 Native 页面 owner 的窗口预加载，不关闭全局 pipeline。
     * 不提供 shutdown()：这是应用级单例，取消 scope 后无法安全重建。
     */
    fun cancelNativeWindow(ownerKey: String) {
        cancelOwner(ownerKey)
    }

    private fun ensureDiskCached(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        ownerKey: String,
        source: Source,
        memory: Boolean,
        explicitCookie: String = ""
    ): Deferred<LoadResult> {
        val appContext = context.applicationContext
        val imageLoader = appContext.imageLoader
        val diskCache = imageLoader.diskCache
        val key = cacheKey(url)

        if (memory && imageLoader.memoryCache?.get(MemoryCache.Key(key)) != null) {
            return CompletableDeferred(LoadResult.MEMORY_HIT)
        }

        if (diskCache != null && openSnapshot(diskCache, key)?.use { true } == true) {
            return CompletableDeferred(LoadResult.DISK_HIT)
        }

        synchronized(lock) {
            ownerUrlKeys.getOrPut(ownerKey) { mutableSetOf() }.add(key)

            val existing = inFlight[key]
            if (existing != null) {
                existing.owners.add(ownerKey)
                return existing.deferred
            }

            val deferredRef = AtomicReference<Deferred<LoadResult>?>(null)
            val deferred = pipelineScope.async(start = CoroutineStart.LAZY) {
                try {
                    withPermitFor(source) {
                        executeCoilLoad(
                            context = appContext,
                            url = url,
                            headers = headers,
                            source = source,
                            memory = memory,
                            explicitCookie = explicitCookie
                        )
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) {
                        LoadResult.CANCELLED
                    } else {
                        Log.w(TAG, "Load failed [$source]: $url", t)
                        LoadResult.NETWORK_FAILED
                    }
                } finally {
                    synchronized(lock) {
                        val current = inFlight[key]
                        if (current?.deferred === deferredRef.get()) {
                            inFlight.remove(key)

                            current?.owners?.forEach { owner ->
                                ownerUrlKeys[owner]?.remove(key)
                                if (ownerUrlKeys[owner]?.isEmpty() == true) {
                                    ownerUrlKeys.remove(owner)
                                }
                            }
                        }
                    }
                }
            }

            deferredRef.set(deferred)
            inFlight[key] = InFlightLoad(
                cacheKey = key,
                url = url,
                source = source,
                deferred = deferred,
                owners = mutableSetOf(ownerKey)
            )

            deferred.start()
            return deferred
        }
    }

    private suspend fun executeCoilLoad(
        context: Context,
        url: String,
        headers: Map<String, String>,
        source: Source,
        memory: Boolean,
        explicitCookie: String
    ): LoadResult {
        val key = cacheKey(url)
        val imageLoader = context.imageLoader

        if (memory && imageLoader.memoryCache?.get(MemoryCache.Key(key)) != null) {
            return LoadResult.MEMORY_HIT
        }

        imageLoader.diskCache?.let { diskCache ->
            if (openSnapshot(diskCache, key)?.use { true } == true) {
                return LoadResult.DISK_HIT
            }
        }

        val request = ImageRequest.Builder(context)
            .data(url)
            .diskCacheKey(key)
            .memoryCacheKey(MemoryCache.Key(key))
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .applyDefaultHeaders(url = url, explicitCookie = explicitCookie, headers = headers)
            .build()

        val result = imageLoader.execute(request)
        return if (result is SuccessResult) {
            LoadResult.NETWORK_SUCCESS
        } else {
            Log.w(TAG, "Coil returned non-success result [$source]: $url")
            LoadResult.NETWORK_FAILED
        }
    }

    private suspend fun <T> withPermitFor(source: Source, block: suspend () -> T): T {
        return when (source) {
            Source.WEBVIEW_TAKEOVER,
            Source.HANDOFF_HOT,
            Source.NATIVE_HOT_WINDOW -> highSemaphore.withPermit { block() }

            Source.NATIVE_WARM_WINDOW -> warmSemaphore.withPermit { block() }

            Source.HANDOFF_COLD,
            Source.NATIVE_COLD_WINDOW,
            Source.CHAPTER_COLD_PREFETCH -> coldSemaphore.withPermit { block() }
        }
    }

    private fun buildNativeWindowSpecs(
        urls: List<String>,
        currentIndex: Int,
        isVerticalMode: Boolean,
        isRtl: Boolean
    ): List<PrefetchSpec> {
        val aheadOffsets = 1..10
        val behindOffsets = -1 downTo -2

        val specs = mutableListOf<PrefetchSpec>()
        var rank = 0

        fun add(offset: Int, source: Source, memory: Boolean) {
            val index = currentIndex + offset
            val imgUrl = urls.getOrNull(index) ?: return
            specs += PrefetchSpec(
                url = imgUrl,
                source = source,
                memory = memory,
                rank = rank++
            )
        }

        aheadOffsets.forEach { offset ->
            when (offset) {
                1, 2, 3 -> add(offset, Source.NATIVE_HOT_WINDOW, memory = true)
                in 4..7 -> add(offset, Source.NATIVE_WARM_WINDOW, memory = false)
                else -> add(offset, Source.NATIVE_COLD_WINDOW, memory = false)
            }
        }

        behindOffsets.forEach { offset ->
            add(offset, Source.NATIVE_HOT_WINDOW, memory = true)
        }

        return specs.distinctBy { cacheKey(it.url) }
    }

    private fun releaseOwnerFromUrl(ownerKey: String, cacheKey: String) {
        synchronized(lock) {
            ownerUrlKeys[ownerKey]?.remove(cacheKey)
            val load = inFlight[cacheKey] ?: return
            load.owners.remove(ownerKey)
            if (load.owners.isEmpty() && load.source != Source.WEBVIEW_TAKEOVER) {
                load.deferred.cancel()
                inFlight.remove(cacheKey)
            }
        }
    }

    private fun ImageRequest.Builder.applyDefaultHeaders(
        url: String,
        explicitCookie: String,
        headers: Map<String, String>
    ): ImageRequest.Builder {
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) addHeader(key, value)
        }

        val cookie = explicitCookie.ifBlank {
            try {
                CookieManager.getInstance().getCookie(url)
                    ?: CookieManager.getInstance().getCookie(REFERER)
                    ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        if (cookie.isNotBlank()) setHeader("Cookie", cookie)
        if (url.contains("yamibo.com", ignoreCase = true)) setHeader("Referer", REFERER)

        return this
    }

    private fun cacheKey(url: String): String {
        // 兼容我们 force reload 追加的 _t 参数，保证强刷不会制造多个缓存 key。
        // 使用 raw string，避免 Kotlin 普通字符串里的 \d 转义编译错误。
        return url
            .replace(Regex("""([?&])_t=\d+(&?)""")) { match ->
                val prefix = match.groupValues[1]
                val suffix = match.groupValues[2]
                when {
                    prefix == "?" && suffix == "&" -> "?"
                    prefix == "?" -> ""
                    suffix == "&" -> "&"
                    else -> ""
                }
            }
            .removeSuffix("?")
            .removeSuffix("&")
    }

    private fun cacheBustingUrl(url: String, forceVersion: Int): String {
        if (forceVersion <= 0) return url
        return if (url.contains("?")) "$url&_t=$forceVersion" else "$url?_t=$forceVersion"
    }

    private fun openSnapshot(diskCache: DiskCache, key: String): DiskCache.Snapshot? {
        return try {
            diskCache.openSnapshot(key)
        } catch (_: Throwable) {
            null
        }
    }

    private fun createResponse(url: String, snapshot: DiskCache.Snapshot): WebResourceResponse {
        val inputStream = SnapshotInputStream(snapshot)
        return WebResourceResponse(getMimeType(url), null, inputStream).apply {
            responseHeaders = mutableMapOf(
                "Cache-Control" to "public, max-age=31536000",
                "Access-Control-Allow-Origin" to "*"
            )
        }
    }

    private fun getMimeType(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains(".png") -> "image/png"
            lowerUrl.contains(".gif") -> "image/gif"
            lowerUrl.contains(".webp") -> "image/webp"
            lowerUrl.contains(".bmp") -> "image/bmp"
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private class SnapshotInputStream(
        private val snapshot: DiskCache.Snapshot,
        private val delegate: InputStream = snapshot.data.toFile().inputStream()
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray): Int = delegate.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun available(): Int = delegate.available()

        override fun close() {
            try {
                delegate.close()
            } finally {
                snapshot.close()
            }
        }
    }
}
