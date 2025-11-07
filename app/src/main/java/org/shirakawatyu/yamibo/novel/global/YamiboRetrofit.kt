package org.shirakawatyu.yamibo.novel.global

import okhttp3.OkHttpClient
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class YamiboRetrofit {

    companion object {
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
                val request = original.newBuilder()
                    .header("User-Agent", RequestConfig.UA)
                    .header("Accept", RequestConfig.ACCEPT)
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