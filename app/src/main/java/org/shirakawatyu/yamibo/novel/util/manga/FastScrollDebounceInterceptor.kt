package org.shirakawatyu.yamibo.novel.util.manga

import coil.imageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import kotlinx.coroutines.delay
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit

/**
 * 快速滑动防抖。
 */
class FastScrollDebounceInterceptor(
    private val delayMs: Long = 200L
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data.toString()

        if (delayMs <= 0L || !data.startsWith("http", ignoreCase = true)) {
            return chain.proceed(request)
        }

        val cacheKey = request.diskCacheKey
            ?: request.memoryCacheKey?.key
            ?: data

        val inMemory = request.context.imageLoader.memoryCache
            ?.get(MemoryCache.Key(cacheKey)) != null

        if (!inMemory) {
            val inDisk = YamiboRetrofit.isImageCachedInCoilDisk(cacheKey)

            if (!inDisk) {
                delay(delayMs)
            }
        }

        return chain.proceed(request)
    }
}