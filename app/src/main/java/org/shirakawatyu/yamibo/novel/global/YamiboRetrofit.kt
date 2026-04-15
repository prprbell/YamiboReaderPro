package org.shirakawatyu.yamibo.novel.global

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.shirakawatyu.yamibo.novel.YamiboApplication
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.util.TtlDnsCache
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DynamicDns(private val bootstrapClient: OkHttpClient) : okhttp3.Dns {

    private val aliDns by lazy {
        DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("223.6.6.6")))
            .includeIPv6(false).build()
    }

    private val tencentDns by lazy {
        DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://doh.pub/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("1.12.12.12"), InetAddress.getByName("120.53.53.53")))
            .includeIPv6(false).build()
    }

    private val systemDns = okhttp3.Dns.SYSTEM

    override fun lookup(hostname: String): List<InetAddress> {
        if (!GlobalData.isCustomDnsEnabled.value) {
            return systemDns.lookup(hostname)
        }

        return runBlocking {
            val channel = Channel<List<InetAddress>>(1)
            val errorCount = AtomicInteger(0)
            val racers = listOf(aliDns, tencentDns)

            val raceJob = launch(Dispatchers.IO) {
                racers.forEach { dns ->
                    launch {
                        try {
                            val result = dns.lookup(hostname)
                            if (result.isNotEmpty()) {
                                channel.trySend(result)
                            }
                        } catch (e: Exception) {
                            if (errorCount.incrementAndGet() == racers.size) {
                                channel.close(e)
                            }
                        }
                    }
                }
            }

            try {
                withTimeout(1500) {
                    channel.receive()
                }
            } catch (_: Exception) {
                systemDns.lookup(hostname)
            } finally {
                raceJob.cancel()
            }
        }
    }
}

class YamiboRetrofit {

    companion object {
        private val pcUaList = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"
        )

        private val currentPcUa = pcUaList.random()

        private val acceptLanguage by lazy {
            val locale = Locale.getDefault()
            "${locale.language}-${locale.country},${locale.language};q=0.9,en-US;q=0.8,en;q=0.7"
        }

        val okHttpClient: OkHttpClient by lazy {
            createOkHttpClient()
        }

        private val YamiboInstance: Retrofit by lazy {
            Retrofit.Builder()
                .baseUrl(RequestConfig.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build()
        }

        fun getInstance(): Retrofit {
            return YamiboInstance
        }

        private fun createOkHttpClient(): OkHttpClient {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .build()

            // 基础解析层
            val raceDns = DynamicDns(bootstrapClient)

            // 缓存装饰层
            val cachedDns = TtlDnsCache(delegate = raceDns)

            return OkHttpClient.Builder()
                .dns(cachedDns)
                .addInterceptor { chain ->
                    val cookie = GlobalData.currentCookie
                    val original = chain.request()

                    val existingUa = original.header("User-Agent")
                    val isPcPseudoRequest =
                        existingUa?.contains("Windows NT") == true || existingUa?.contains("Macintosh") == true

                    val finalUa = if (isPcPseudoRequest) {
                        currentPcUa
                    } else {
                        YamiboApplication.systemUserAgent.ifEmpty { RequestConfig.UA }
                    }

                    val request = original.newBuilder()
                        .header("User-Agent", finalUa)
                        .header("Accept", RequestConfig.ACCEPT)
                        .header("Accept-Language", acceptLanguage)
                        .header("Cookie", cookie)
                        .method(original.method, original.body)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }

        fun proxyWebViewResource(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
            val url = request.url.toString()
            if (request.method != "GET" || url.startsWith("data:")) return null

            try {
                val reqBuilder = okhttp3.Request.Builder().url(url)

                request.requestHeaders?.forEach { (key, value) ->
                    reqBuilder.header(key, value)
                }

                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    reqBuilder.header("Cookie", cookie)
                }

                val response = okHttpClient.newCall(reqBuilder.build()).execute()

                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type", "") ?: ""
                    val mimeType = contentType.substringBefore(";").trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream"
                    val encoding = contentType.substringAfter("charset=", "utf-8").replace("\"", "").trim()

                    val inputStream = response.body?.byteStream()
                    if (inputStream != null) {
                        return android.webkit.WebResourceResponse(mimeType, encoding, inputStream)
                    }
                }
            } catch (_: Exception) {
                return null
            }
            return null
        }
    }
}