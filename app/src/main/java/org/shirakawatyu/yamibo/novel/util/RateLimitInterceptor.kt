package org.shirakawatyu.yamibo.novel.util

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 高性能无锁并发匀速拦截器 (Lock-Free Rate Limiter)
 * 采用 CAS 机制替代 synchronized 重量级锁，避免 OkHttp 线程池饥饿
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