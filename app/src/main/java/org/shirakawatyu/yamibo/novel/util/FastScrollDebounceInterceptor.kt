package org.shirakawatyu.yamibo.novel.util

import coil.imageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import kotlinx.coroutines.delay
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit


class FastScrollDebounceInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data.toString()

        if (data.startsWith("http", ignoreCase = true)) {

            val memoryKey = request.memoryCacheKey?.key ?: data

            val inMemory = request.context.imageLoader.memoryCache?.get(MemoryCache.Key(memoryKey)) != null

            if (!inMemory) {
                val inDisk = YamiboRetrofit.isImageCachedInOkHttp(data)

                if (!inDisk) {
                    delay(200L)
                }
            }
        }

        return chain.proceed(request)
    }
}