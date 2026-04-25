package org.shirakawatyu.yamibo.novel.util.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 匀速拦截器
 */
class RateLimitInterceptor(
    private val minIntervalMs: Long = 100L
) : Interceptor {

    private val nextAvailableTime = AtomicLong(0L)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val now = System.currentTimeMillis()

        val targetNextTime = nextAvailableTime.updateAndGet { currentNext ->
            maxOf(now, currentNext) + minIntervalMs
        }

        val currentExecuteTime = targetNextTime - minIntervalMs
        val timeToWait = currentExecuteTime - now

        if (timeToWait > 0) {
            try {
                Thread.sleep(timeToWait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        return chain.proceed(request)
    }
}