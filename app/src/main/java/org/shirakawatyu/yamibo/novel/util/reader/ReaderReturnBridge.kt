package org.shirakawatyu.yamibo.novel.util.reader

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * ReaderPage <-> 原贴 WebView 的一次性桥接状态。
 */
object ReaderReturnBridge {
    data class ReaderContext(
        val readerUrl: String,
        val tid: String?,
        val authorId: String?,
        val readerWebPage: Int,
        val readerPageIndex: Int,
        val cacheTitle: String?,
        val originalPostUrl: String
    )

    data class OriginalPostRequest(
        val id: Long,
        val targetUrl: String,
        val tid: String?
    )

    data class ReaderJump(
        val id: Long,
        val tid: String?,
        val readerUrl: String,
        val webPage: Int,
        val readerPageIndex: Int?,
        val allowCache: Boolean,
        val cacheTitle: String?
    )

    data class PendingCacheRefresh(
        val url: String,
        val pageNum: Int,
        val authorId: String?,
        val cacheTitle: String?
    )

    private val pendingCacheRefreshState = mutableStateOf<PendingCacheRefresh?>(null)
    var pendingCacheRefresh: PendingCacheRefresh?
        get() = pendingCacheRefreshState.value
        set(value) {
            pendingCacheRefreshState.value = value
        }

    private val idGenerator = AtomicLong(0L)

    private val contextState = mutableStateOf<ReaderContext?>(null)
    var context: ReaderContext?
        get() = contextState.value
        set(value) {
            contextState.value = value
        }

    private val originalPostRequestState = mutableStateOf<OriginalPostRequest?>(null)
    var originalPostRequest: OriginalPostRequest?
        get() = originalPostRequestState.value
        private set(value) {
            originalPostRequestState.value = value
        }

    private val pendingJumpState = mutableStateOf<ReaderJump?>(null)
    var pendingJump: ReaderJump?
        get() = pendingJumpState.value
        private set(value) {
            pendingJumpState.value = value
        }

    fun captureFromReader(
        readerUrl: String,
        authorId: String?,
        currentView: Int,
        readerPageIndex: Int,
        cacheTitle: String? = null
    ): ReaderContext {
        val baseReaderUrl = stripReaderTransientParams(toAbsoluteBbsUrl(readerUrl))
        val tid = extractTid(baseReaderUrl)
        val targetUrl = buildReaderUrl(baseReaderUrl, currentView, authorId)
        return ReaderContext(
            readerUrl = baseReaderUrl,
            tid = tid,
            authorId = authorId,
            readerWebPage = currentView.coerceAtLeast(1),
            readerPageIndex = readerPageIndex.coerceAtLeast(0),
            cacheTitle = normalizeCacheTitle(cacheTitle),
            originalPostUrl = targetUrl
        ).also { context = it }
    }

    fun requestOriginalPost(targetUrl: String) {
        originalPostRequest = OriginalPostRequest(
            id = idGenerator.incrementAndGet(),
            targetUrl = targetUrl,
            tid = extractTid(targetUrl)
        )
    }

    fun clearOriginalPostRequest(id: Long) {
        if (originalPostRequest?.id == id) originalPostRequest = null
    }

    fun requestReaderJump(
        tid: String?,
        readerUrl: String,
        webPage: Int,
        readerPageIndex: Int?,
        allowCache: Boolean = false,
        cacheTitle: String? = null
    ) {
        pendingJump = ReaderJump(
            id = idGenerator.incrementAndGet(),
            tid = tid,
            readerUrl = stripReaderTransientParams(toAbsoluteBbsUrl(readerUrl)),
            webPage = webPage.coerceAtLeast(1),
            readerPageIndex = readerPageIndex?.coerceAtLeast(0),
            allowCache = allowCache,
            cacheTitle = normalizeCacheTitle(cacheTitle)
        )
    }

    fun takePendingJumpForUrl(readerUrl: String): ReaderJump? {
        val pending = pendingJump ?: return null
        val pendingTid = pending.tid ?: extractTid(pending.readerUrl)
        val currentTid = extractTid(readerUrl)
        if (pendingTid != null && currentTid != null && pendingTid != currentTid) return null
        pendingJump = null
        return pending
    }

    fun toAbsoluteBbsUrl(url: String): String {
        val clean = url.trim()
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "https://bbs.yamibo.com$clean"
            else -> "https://bbs.yamibo.com/$clean"
        }
    }

    fun extractTid(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val decoded = Uri.decode(url)
        Regex("[?&](?:tid|ptid)=(\\d+)").find(decoded)?.let { return it.groupValues[1] }
        Regex("thread-(\\d+)-").find(decoded)?.let { return it.groupValues[1] }
        return null
    }

    fun extractAuthorId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return Regex("[?&]authorid=(\\d+)").find(Uri.decode(url))?.groupValues?.getOrNull(1)
    }

    fun extractPage(url: String?): Int {
        if (url.isNullOrBlank()) return 1
        val decoded = Uri.decode(url)
        Regex("[?&]page=(\\d+)").find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it.coerceAtLeast(1) }
        Regex("thread-\\d+-(\\d+)-").find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it.coerceAtLeast(1) }
        return 1
    }

    fun stripReaderTransientParams(url: String): String {
        var result = url.substringBefore("#")
        listOf("page", "authorid", "jumpPid", "readerPage").forEach { key ->
            result = result.replace(Regex("([?&])$key=[^&#]*&?"), "$1")
        }
        return cleanupUrl(result)
    }

    fun buildReaderUrl(baseReaderUrl: String, page: Int, authorId: String?): String {
        var target = stripReaderTransientParams(baseReaderUrl)
        if (authorId != null && !target.contains(Regex("[?&]authorid="))) {
            target = appendQuery(target, "authorid", authorId)
        }
        target = appendQuery(target, "page", page.coerceAtLeast(1).toString())
        return cleanupUrl(target)
    }

    fun sameUrlIgnoringHashAndTrailingSlash(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun normalize(value: String): String = cleanupUrl(value.substringBefore("#")).trimEnd('/')
        return normalize(a) == normalize(b)
    }

    fun encodeRouteArg(value: String): String = URLEncoder.encode(value, "utf-8")

    private fun normalizeCacheTitle(title: String?): String? {
        return title
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun appendQuery(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=$value"
    }

    private fun cleanupUrl(url: String): String {
        var result = url
            .replace("?&", "?")
            .replace(Regex("&&+"), "&")
            .replace(Regex("[?&]$"), "")
        while (result.endsWith("?") || result.endsWith("&")) {
            result = result.dropLast(1)
        }
        return result
    }
}
