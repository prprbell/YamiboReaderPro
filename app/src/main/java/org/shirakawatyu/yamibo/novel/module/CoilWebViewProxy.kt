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

/**
 * 网络接管核心：
 * 将WebView的请求代理给Coil，且实现与WebView生命周期的解耦。
 * 即便WebView被stopLoading掐断，后端的Coil下载任务依然坚挺，确保流量0浪费。
 */
@OptIn(ExperimentalCoilApi::class)
object CoilWebViewProxy {
    // 独立于UI和WebView的全局IO作用域
    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 用于确保同一个URL不会被并发发起多次请求
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<DiskCache.Snapshot?>>()

    fun interceptImage(
        context: Context,
        url: String,
        headers: Map<String, String>?
    ): WebResourceResponse? {
        val imageLoader = context.imageLoader
        val diskCache = imageLoader.diskCache ?: return null

        // 如果在磁盘缓存中已有，直接返回本地文件流
        diskCache.openSnapshot(url)?.let { snapshot ->
            return createResponse(url, snapshot)
        }

        // 发起一个“不可杀死的”后台预加载任务
        val deferred = inFlightRequests.getOrPut(url) {
            proxyScope.async {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        // WebView自身有极好的内存管理，所以 Coil 这里直接跳过内存，专攻磁盘
                        .memoryCachePolicy(CachePolicy.DISABLED) 
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }
                        .build()
                    
                    // 同步执行该请求
                    imageLoader.execute(request)
                    
                    // 下载完成后，尝试再次开启快照
                    diskCache.openSnapshot(url)
                } catch (e: Exception) {
                    Log.e("CoilProxy", "拦截下载失败: $url", e)
                    null
                } finally {
                    inFlightRequests.remove(url)
                }
            }
        }

        // 3. 让 WebView 线程阻塞等待。
        // 如果用户此时点击了图片进入 Native，WebView 会执行 stopLoading()，
        // 这里的 runBlocking 会抛出中断异常 (CancellationException 等)。
        // 但由于 deferred 属于 proxyScope，它并不会被取消，它会继续在后台下完剩下的数据
        return try {
            runBlocking {
                val snapshot = deferred.await()
                snapshot?.let { createResponse(url, it) }
            }
        } catch (e: Exception) {
            Log.d("CoilProxy", "WebView 页面跳转中断了请求，交接给后台 Coil 继续下载: $url")
            null // 返回 null 让 WebView 放弃本次读取
        }
    }

    private fun createResponse(url: String, snapshot: DiskCache.Snapshot): WebResourceResponse {
        val inputStream = SnapshotInputStream(snapshot)
        return WebResourceResponse(getMimeType(url), null, inputStream).apply {
            val responseHeaders = mutableMapOf<String, String>()
            // 强缓存一年，既然全权交给了 Coil 磁盘，WebView 就不要频繁来骚扰了
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

    /**
     * 安全的输入流包装类。
     * Coil 的 DiskCache.Snapshot 本质上是一个文件锁，如果我们只交出 InputStream，
     * WebView 读取完毕后只会关闭 stream，不会关闭 Snapshot，导致缓存文件死锁或无法被 LRU 淘汰。
     * 这里使用包装模式，确保 close() 被调用时联动释放 Snapshot。
     */
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
            try { 
                delegate.close() 
            } finally { 
                snapshot.close() 
            }
        }
    }
}