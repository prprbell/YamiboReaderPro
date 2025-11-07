package org.shirakawatyu.yamibo.novel.global

import okhttp3.OkHttpClient
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class YamiboRetrofit {

    companion object {
        private var lastCookie: String = ""
        private var instance: Retrofit = getInstance("")

        fun getInstance(cookie: String): Retrofit {
            if (lastCookie == cookie) {
                return instance
            }
            val httpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", RequestConfig.UA)
                    .header("Accept", RequestConfig.ACCEPT)
                    .header("Cookie", cookie)
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }.build()

            instance = Retrofit.Builder()
                .baseUrl(RequestConfig.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(httpClient)
                .build()
            lastCookie = cookie
            return instance
        }
    }
}