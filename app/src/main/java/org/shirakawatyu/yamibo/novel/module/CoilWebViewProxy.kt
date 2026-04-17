package org.shirakawatyu.yamibo.novel.module

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoilApi::class)
object CoilWebViewProxy {
    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<Unit>>()

    fun interceptImage(
        context: Context,
        url: String,
        headers: Map<String, String>?
    ): WebResourceResponse? {
        val imageLoader = context.imageLoader
        val diskCache = imageLoader.diskCache ?: return null

        diskCache.openSnapshot(url)?.let { snapshot ->
            return createResponse(url, snapshot)
        }

        val deferred = inFlightRequests.getOrPut(url) {
            proxyScope.async {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }
                        .build()

                    imageLoader.execute(request)
                } catch (e: Exception) {
                    Log.e("CoilProxy", "拦截下载失败: $url", e)
                } finally {
                    inFlightRequests.remove(url)
                }
                Unit
            }
        }

        return try {
            runBlocking {
                deferred.await()
                diskCache.openSnapshot(url)?.let { createResponse(url, it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createResponse(url: String, snapshot: DiskCache.Snapshot): WebResourceResponse {
        val inputStream = SnapshotInputStream(snapshot)
        return WebResourceResponse(getMimeType(url), null, inputStream).apply {
            val responseHeaders = mutableMapOf<String, String>()
            responseHeaders["Cache-Control"] = "public, max-age=31536000"
            responseHeaders["Access-Control-Allow-Origin"] = "*"
            this.responseHeaders = responseHeaders
        }
    }

    private fun getMimeType(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains(".png") -> "image/png"
            lowerUrl.contains(".gif") -> "image/gif"
            lowerUrl.contains(".webp") -> "image/webp"
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    class SnapshotInputStream(
        private val snapshot: DiskCache.Snapshot,
        private val delegate: InputStream = snapshot.data.toFile().inputStream()
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray): Int = delegate.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun available(): Int = delegate.available()
        override fun close() {
            snapshot.use { snapshot ->
                delegate.close()
            }
        }
    }
}