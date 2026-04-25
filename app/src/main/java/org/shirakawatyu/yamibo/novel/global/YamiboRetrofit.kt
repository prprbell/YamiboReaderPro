package org.shirakawatyu.yamibo.novel.global

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import coil.imageLoader
import okhttp3.Dns
import okhttp3.HttpUrl
import org.shirakawatyu.yamibo.novel.YamiboApplication
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.util.manga.ImageCheckerUtil
import org.shirakawatyu.yamibo.novel.util.network.RateLimitInterceptor
import org.shirakawatyu.yamibo.novel.util.network.TtlDnsCache
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    private val systemDns = Dns.SYSTEM

    private val manualDnsCache = ConcurrentHashMap<String, Dns>()

    private fun getBootstrapHostsForDoHUrl(url: HttpUrl): List<InetAddress> {
        val host = url.host.lowercase()
        return when {
            host.contains("alidns") || host.contains("dns.aliyun") ->
                listOf(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("223.6.6.6"))
            host.contains("doh.pub") || host.contains("dnspod") ->
                listOf(InetAddress.getByName("1.12.12.12"), InetAddress.getByName("120.53.53.53"))
            host.contains("cloudflare") || host.contains("1.1.1.1") ->
                listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            host.contains("google") || host.contains("dns.google") ->
                listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
            else -> listOf(InetAddress.getByName("223.5.5.5"))
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val enabled = GlobalData.isDnsOptimizationEnabled.value
        if (!enabled) return systemDns.lookup(hostname)

        val mode = GlobalData.dnsOptimizationMode.value
        if (mode == "manual") {
            val url = GlobalData.customDnsUrl.value
            if (url.isBlank() || !url.startsWith("https://")) return systemDns.lookup(hostname)

            return try {
                val manualDns = manualDnsCache.getOrPut(url) {
                    DnsOverHttps.Builder().client(bootstrapClient)
                        .url(url.toHttpUrl())
                        .bootstrapDnsHosts(getBootstrapHostsForDoHUrl(url.toHttpUrl()))
                        .includeIPv6(false)
                        .build()
                }
                manualDns.lookup(hostname)
            } catch (_: Exception) {
                systemDns.lookup(hostname)
            }
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

        private val sharedConnectionPool = ConnectionPool(
            maxIdleConnections = 8,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        )

        private val sharedBootstrapClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        private val sharedDns by lazy {
            TtlDnsCache(delegate = DynamicDns(sharedBootstrapClient))
        }

        // 基础客户端
        val okHttpClient: OkHttpClient by lazy {
            createOkHttpClient("http_cache_default", 50L * 1024 * 1024, enableCache = true, enableImageChecker = false)
        }

        val threadOkHttpClient: OkHttpClient by lazy {
            createOkHttpClient("http_cache_thread", 0L, enableCache = false, enableImageChecker = true,maxRequestsPerHost = 6)
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

        private fun createOkHttpClient(
            cacheDirName: String,
            cacheSize: Long,
            enableCache: Boolean,
            enableImageChecker: Boolean,
            maxRequestsPerHost: Int = 5
        ): OkHttpClient {
            val customDispatcher = okhttp3.Dispatcher().apply {
                this.maxRequestsPerHost = maxRequestsPerHost
            }
            val builder = OkHttpClient.Builder()
                .dispatcher(customDispatcher)
                .dns(sharedDns)
                .connectionPool(sharedConnectionPool)
                .pingInterval(20, TimeUnit.SECONDS)

            if (enableCache) {
                val cacheDir = File(YamiboApplication.globalCacheDir, cacheDirName)
                builder.cache(okhttp3.Cache(cacheDir, cacheSize))
            }

            // 1. 应用拦截器
            builder.addInterceptor { chain ->
                // 前置网络状态检查
                val cm = YamiboApplication.application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
                val isInternetReady = capabilities?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } ?: false

                if (!isInternetReady) {
                    throw java.io.IOException("Network is not validated (Disconnected or Captive Portal)")
                }

                val original = chain.request()
                if (!original.url.host.contains("yamibo.com")) {
                    return@addInterceptor chain.proceed(original)
                }

                val cookie = GlobalData.currentCookie
                val existingUa = original.header("User-Agent")
                val isPcPseudoRequest =
                    existingUa?.contains("Windows NT") == true || existingUa?.contains("Macintosh") == true

                val finalUa = if (isPcPseudoRequest) {
                    currentPcUa
                } else {
                    YamiboApplication.systemUserAgent.ifEmpty { RequestConfig.UA }
                }

                val requestBuilder = original.newBuilder()
                    .header("User-Agent", finalUa)
                    .header("Accept", RequestConfig.ACCEPT)
                    .header("Accept-Language", acceptLanguage)
                    .header("Cookie", cookie)

                if (original.url.host.contains("yamibo.com")) {
                    requestBuilder.header("Referer", "https://bbs.yamibo.com/")
                }

                val request = requestBuilder
                    .method(original.method, original.body)
                    .build()

                chain.proceed(request)
            }
            if (enableImageChecker) {
                builder.addNetworkInterceptor(RateLimitInterceptor(100L))
            }
            // 2. 网络拦截器
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val rawResponse = chain.proceed(request)
                val urlStr = request.url.toString()

                val checkedResponse = if (enableImageChecker) {
                    try {
                        ImageCheckerUtil.interceptAndCheckImageStream(rawResponse, urlStr)
                    } catch (e: Exception) {
                        throw IOException("Blocked by ImageChecker: ${e.message}", e)
                    }
                } else {
                    rawResponse
                }

                val isForumImage = urlStr.contains("attachment/forum", ignoreCase = true)

                if (checkedResponse.isSuccessful && urlStr.contains(Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE))) {
                    if (isForumImage) {
                        val maxAge = 60 * 60 * 2
                        return@addNetworkInterceptor checkedResponse.newBuilder()
                            .header("Cache-Control", "public, max-age=$maxAge")
                            .removeHeader("Pragma")
                            .build()
                    } else {
                        val baseMaxAge = 60 * 60 * 24 * 7
                        val maxAge = baseMaxAge + kotlin.random.Random.nextInt(-86400, 86400)
                        val swr = 60 * 60 * 24 * 1
                        return@addNetworkInterceptor checkedResponse.newBuilder()
                            .header("Cache-Control", "public, max-age=$maxAge, stale-while-revalidate=$swr")
                            .removeHeader("Pragma")
                            .build()
                    }
                }
                checkedResponse
            }

            return builder.build()
        }

        fun proxyWebViewResource(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
            val url = request.url.toString()
            if (request.method != "GET" || url.startsWith("data:")) return null
            val isForumImage = url.contains("attachment/forum", ignoreCase = true)
            val client = if (isForumImage) threadOkHttpClient else okHttpClient

            try {
                val reqBuilder = okhttp3.Request.Builder().url(url)
                request.requestHeaders?.forEach { (key, value) -> reqBuilder.header(key, value) }
                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    reqBuilder.header("Cookie", cookie)
                }

                if (url.contains("yamibo.com", ignoreCase = true)) {
                    reqBuilder.header("Referer", "https://bbs.yamibo.com/")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type", "") ?: ""
                    val mimeType = contentType.substringBefore(";").trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream"
                    val encoding = if (contentType.contains("charset=")) {
                        contentType.substringAfter("charset=").replace("\"", "").trim()
                    } else null

                    val inputStream = response.body?.byteStream()
                    if (inputStream != null) {
                        val webResourceResponse = android.webkit.WebResourceResponse(mimeType, encoding, inputStream)
                        webResourceResponse.setStatusCodeAndReasonPhrase(response.code, response.message.ifBlank { "OK" })
                        val responseHeaders = mutableMapOf<String, String>()
                        response.headers.forEach { (name, value) ->
                            if (name.equals("content-encoding", true) && value.equals("br", true)) return@forEach
                            responseHeaders[name] = value
                        }
                        webResourceResponse.responseHeaders = responseHeaders
                        return webResourceResponse
                    }
                }
            } catch (_: Exception) { return null }
            return null
        }

        @OptIn(ExperimentalCoilApi::class)
        fun isImageCachedInCoilDisk(cacheKey: String): Boolean {
            return try {
                val diskCache = YamiboApplication.application.imageLoader.diskCache ?: return false
                diskCache.openSnapshot(cacheKey)?.use {
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
        }
    }
}