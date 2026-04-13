package org.shirakawatyu.yamibo.novel.global

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.shirakawatyu.yamibo.novel.YamiboApplication
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

class DynamicDns(private val customDns: okhttp3.Dns, private val systemDns: okhttp3.Dns = okhttp3.Dns.SYSTEM) : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return if (GlobalData.isCustomDnsEnabled.value) {
            try {
                customDns.lookup(hostname)
            } catch (e: Exception) {
                systemDns.lookup(hostname)
            }
        } else {
            systemDns.lookup(hostname)
        }
    }
}

class YamiboRetrofit {

    // 1. 定义 DoH 数据结构
    private data class DohServer(val url: String, val ips: List<String>)

    companion object {
        private val pcUaList = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"
        )

        private val currentPcUa = pcUaList.random()

        private val dohServerList = listOf(
            // 阿里云公共 DNS
            DohServer("https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
            // 腾讯云 DNSPod
            DohServer("https://doh.pub/dns-query", listOf("1.12.12.12", "120.53.53.53"))
        )

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
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val selectedDoh = dohServerList.random()

            val dns = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(selectedDoh.url.toHttpUrl())
                .bootstrapDnsHosts(
                    selectedDoh.ips.map { InetAddress.getByName(it) }
                )
                .includeIPv6(false)
                .build()

            return OkHttpClient.Builder()
                .dns(DynamicDns(dns))
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