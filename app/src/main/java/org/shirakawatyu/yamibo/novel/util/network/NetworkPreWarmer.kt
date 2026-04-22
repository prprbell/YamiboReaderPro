package org.shirakawatyu.yamibo.novel.util.network

import okhttp3.Request
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit

/**
 * 务实型网络优化：在 App 启动的空闲期，提前完成 DNS、TCP、TLS 握手。
 */
object NetworkPreWarmer {
    
    fun warmUp() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://bbs.yamibo.com")
                    .head() 
                    .build()

                YamiboRetrofit.okHttpClient.newCall(request).execute().use { _ ->
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}