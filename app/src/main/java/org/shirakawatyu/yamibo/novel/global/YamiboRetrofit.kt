package org.shirakawatyu.yamibo.novel.global

import okhttp3.OkHttpClient
import org.shirakawatyu.yamibo.novel.YamiboApplication
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

class YamiboRetrofit {

    companion object {
        // PC UA 列表：使用相对较新的主流浏览器版本池
        private val pcUaList = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"
        )

        // 每次App启动时随机固定一个PC UA，避免在单次会话中频繁变动特征
        private val currentPcUa = pcUaList.random()

        private val acceptLanguage by lazy {
            val locale = Locale.getDefault()
            "${locale.language}-${locale.country},${locale.language};q=0.9,en-US;q=0.8,en;q=0.7"
        }

        private val YamiboInstance: Retrofit by lazy {
            createInstance()
        }

        fun getInstance(): Retrofit {
            return YamiboInstance
        }

        private fun createInstance(): Retrofit {
            val httpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->

                val cookie = GlobalData.currentCookie
                val original = chain.request()

                val existingUa = original.header("User-Agent")
                val isPcPseudoRequest =
                    existingUa?.contains("Windows NT") == true || existingUa?.contains("Macintosh") == true

                val finalUa = if (isPcPseudoRequest) {
                    currentPcUa
                } else {
                    if (YamiboApplication.systemUserAgent.isNotEmpty()) YamiboApplication.systemUserAgent else RequestConfig.UA
                }

                val request = original.newBuilder()
                    .header("User-Agent", finalUa)
                    .header("Accept", RequestConfig.ACCEPT)
                    .header("Accept-Language", acceptLanguage)
                    .header("Cookie", cookie)
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }.build()

            return Retrofit.Builder()
                .baseUrl(RequestConfig.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(httpClient)
                .build()
        }
    }
}