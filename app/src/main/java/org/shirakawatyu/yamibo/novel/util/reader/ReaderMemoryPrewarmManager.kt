package org.shirakawatyu.yamibo.novel.util.reader

import android.content.Context
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.NovelApi
import java.util.concurrent.ConcurrentHashMap

/**
 * ReaderWebPage -> ReaderPage 的内存预热管理器。
 *
 * 设计目标：
 * 1. 只写 CacheUtil 内存缓存，不写 LocalCacheUtil 磁盘缓存。
 * 2. 发射前检查磁盘/内存缓存，已有缓存就不发射。
 * 3. 预热任务不绑定 ReaderWebPage 的 Composable scope，FAB 返回后 ReaderVM 仍可继承同一个 Deferred。
 */
object ReaderMemoryPrewarmManager {
    private const val PREWARM_TIMEOUT_MS = 8_000L
    private const val AWAIT_IN_FLIGHT_TIMEOUT_MS = 12_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<PrewarmResult>>()

    data class Target(
        val primaryUrl: String,
        val tid: String,
        val page: Int,
        val authorId: String,
        val aliasUrls: List<String> = emptyList()
    )

    sealed class PrewarmResult {
        data class Success(val data: CacheData) : PrewarmResult()
        data class AlreadyCached(val data: CacheData?) : PrewarmResult()
        data class Failed(val throwable: Throwable? = null) : PrewarmResult()
    }

    fun canonicalReaderUrl(rawUrl: String): String {
        return ReaderReturnBridge.stripReaderTransientParams(
            ReaderReturnBridge.toAbsoluteBbsUrl(rawUrl)
        )
    }

    private fun Target.normalized(): Target {
        val canonicalPrimary = canonicalReaderUrl(primaryUrl)
        val normalizedAliases = buildList {
            add(primaryUrl)
            add(canonicalPrimary)
            aliasUrls.forEach { add(it) }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() && it != canonicalPrimary }
            .distinct()

        return copy(
            primaryUrl = canonicalPrimary,
            page = page.coerceAtLeast(1),
            authorId = authorId.trim(),
            aliasUrls = normalizedAliases
        )
    }

    private fun keyOf(target: Target): String {
        val normalized = target.normalized()
        return "${normalized.primaryUrl}::${normalized.page}::${normalized.authorId}"
    }

    private fun peekUsableMemoryCache(target: Target): CacheData? {
        val normalized = target.normalized()
        val data = CacheUtil.peekCacheCompat(
            primaryUrl = normalized.primaryUrl,
            pageNum = normalized.page,
            aliasUrls = normalized.aliasUrls
        ) ?: return null

        return if (data.authorId == null || data.authorId == normalized.authorId) data else null
    }

    private fun hasDiskCache(context: Context, target: Target): Boolean {
        val normalized = target.normalized()
        return LocalCacheUtil.getInstance(context.applicationContext)
            .getCachedPageNumsCompat(
                primaryUrl = normalized.primaryUrl,
                aliasUrls = normalized.aliasUrls
            )
            .contains(normalized.page)
    }

    /**
     * 只有磁盘/内存都没有缓存时才发射；如果同 key 已在发射中，返回同一个 Deferred。
     * 返回 null 表示已经有缓存，不需要预热。
     */
    fun prewarmIfNeeded(context: Context, target: Target): Deferred<PrewarmResult>? {
        val appContext = context.applicationContext
        val normalized = target.normalized()

        if (hasDiskCache(appContext, normalized)) return null
        peekUsableMemoryCache(normalized)?.let { return null }

        val key = keyOf(normalized)
        inFlight[key]?.let { return it }

        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                if (hasDiskCache(appContext, normalized)) {
                    return@async PrewarmResult.AlreadyCached(null)
                }
                peekUsableMemoryCache(normalized)?.let {
                    return@async PrewarmResult.AlreadyCached(it)
                }

                val data = withTimeout(PREWARM_TIMEOUT_MS) {
                    fetchReaderPage(normalized)
                }

                CacheUtil.saveCache(normalized.primaryUrl, data)
                PrewarmResult.Success(data)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PrewarmResult.Failed(t)
            } finally {
                inFlight.remove(key)
            }
        }

        val existing = inFlight.putIfAbsent(key, deferred)
        return if (existing != null) {
            deferred.cancel()
            existing
        } else {
            deferred.start()
            deferred
        }
    }

    fun getInFlight(target: Target): Deferred<PrewarmResult>? {
        return inFlight[keyOf(target)]
    }

    /**
     * ReaderVM 回来时调用：如果 ReaderWebPage 已经在预热同一页，就等待并复用它的结果。
     * 不存在 in-flight 或等待超时则返回 null，让 ReaderVM 继续自己的网络兜底。
     */
    suspend fun awaitInFlightDataOrNull(
        target: Target,
        timeoutMillis: Long = AWAIT_IN_FLIGHT_TIMEOUT_MS
    ): CacheData? {
        val normalized = target.normalized()
        val deferred = getInFlight(normalized) ?: return null
        val result = withTimeoutOrNull(timeoutMillis) {
            deferred.await()
        } ?: return null

        return when (result) {
            is PrewarmResult.Success -> result.data
            is PrewarmResult.AlreadyCached -> result.data ?: peekUsableMemoryCache(normalized)
            is PrewarmResult.Failed -> null
        }
    }

    private suspend fun fetchReaderPage(target: Target): CacheData {
        val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
        val resp = api.getThreadPageByAuthor(
            tid = target.tid,
            page = target.page,
            authorid = target.authorId
        )

        val json = JSON.parseObject(resp.string())
        val variables = json.getJSONObject("Variables")
            ?: throw IllegalStateException("Variables not found")
        val thread = variables.getJSONObject("thread")
        val ppp = variables.getString("ppp")?.toIntOrNull()?.coerceAtLeast(1) ?: 20
        val totalReplies = thread?.getString("replies")?.toIntOrNull() ?: 0
        val maxPage = ((totalReplies + 1 + ppp - 1) / ppp).coerceAtLeast(1)

        val postlist = variables.getJSONArray("postlist")
            ?: throw IllegalStateException("postlist not found")
        val messages = (0 until postlist.size).map { i ->
            postlist.getJSONObject(i).getString("message")
        }
        val combinedHtml = messages.joinToString("") { message ->
            "<div class=\"message\">$message</div>"
        }

        return CacheData(
            cachedPageNum = target.page,
            htmlContent = combinedHtml,
            maxPageNum = maxPage,
            authorId = target.authorId
        )
    }
}
