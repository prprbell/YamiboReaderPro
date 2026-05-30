package org.shirakawatyu.yamibo.novel.util

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import java.util.concurrent.atomic.AtomicLong
import androidx.core.net.toUri

object StaticAssetProxy {

    val hitCount = AtomicLong()
    val successCount = AtomicLong()
    val mimeRejectCount = AtomicLong()


    private val SAFE_STATIC_EXTENSIONS = setOf(
        "js", "css", "woff", "woff2", "ttf", "eot", "svg", "ico"
    )

    private val SAFE_STATIC_HOSTS = setOf(
        "bbs.yamibo.com",
        "m.yamibo.com",
        "www.yamibo.com",
        "yamibo.com"
    )

    private val DANGEROUS_STATIC_QUERY_KEYS = listOf(
        "auth",
        "token",
        "sid",
        "session",
        "formhash",
        "password",
        "passwd",
        "seccode"
    )

    fun shouldProxySafeDiscuzStaticAsset(request: WebResourceRequest?): Boolean {
        if (request == null) return false
        if (request.isForMainFrame) return false
        if (request.method != "GET") return false

        val uri = request.url ?: return false

        if (uri.scheme != "https") return false

        val host = uri.host.orEmpty().lowercase()
        if (host !in SAFE_STATIC_HOSTS) return false

        val path = uri.path.orEmpty().lowercase()
        if (path.isBlank()) return false

        val ext = path.substringAfterLast('.', missingDelimiterValue = "")
        if (ext !in SAFE_STATIC_EXTENSIONS) return false

        val isSafeStaticPath =
            path.startsWith("/static/") ||
                    path.startsWith("/template/") ||
                    path.startsWith("/data/cache/") ||
                    path.startsWith("/source/plugin/")
        if (!isSafeStaticPath) return false

        val query = uri.encodedQuery.orEmpty()
        val queryLower = query.lowercase()

        if (DANGEROUS_STATIC_QUERY_KEYS.any { queryLower.contains(it) }) {
            return false
        }

        if (ext == "js" || ext == "css") {
            if (query.isBlank()) {
                return false
            }
        }

        return true
    }

    fun isExpectedStaticMime(url: String, mimeType: String?): Boolean {
        val path = runCatching {
            url.toUri().path.orEmpty().lowercase()
        }.getOrDefault("")
        val mime = mimeType.orEmpty().lowercase().substringBefore(";").trim()

        return when {
            path.endsWith(".css") ->
                mime == "text/css"

            path.endsWith(".js") ->
                mime == "application/javascript" ||
                        mime == "text/javascript" ||
                        mime == "application/x-javascript" ||
                        mime == "text/plain" ||
                        mime == "application/octet-stream" ||
                        mime.contains("javascript") ||
                        mime.contains("ecmascript")

            path.endsWith(".woff") ->
                mime == "font/woff" ||
                        mime == "application/font-woff" ||
                        mime == "application/x-font-woff" ||
                        mime == "application/octet-stream"

            path.endsWith(".woff2") ->
                mime == "font/woff2" ||
                        mime == "application/font-woff2" ||
                        mime == "application/octet-stream"

            path.endsWith(".ttf") ->
                mime == "font/ttf" ||
                        mime == "application/x-font-ttf" ||
                        mime == "application/octet-stream"

            path.endsWith(".eot") ->
                mime == "application/vnd.ms-fontobject" ||
                        mime == "application/octet-stream"

            path.endsWith(".svg") ->
                mime == "image/svg+xml" ||
                        mime == "text/xml" ||
                        mime == "application/xml"

            path.endsWith(".ico") ->
                mime == "image/x-icon" ||
                        mime == "image/vnd.microsoft.icon" ||
                        mime == "image/png" ||
                        mime == "application/octet-stream"

            else -> false
        }
    }

    fun closeWebResourceResponseQuietly(response: WebResourceResponse?) {
        try {
            response?.data?.close()
        } catch (_: Throwable) {
        }
    }

    fun tryProxySafeStaticAsset(request: WebResourceRequest?): WebResourceResponse? {
        if (!shouldProxySafeDiscuzStaticAsset(request)) return null

        hitCount.incrementAndGet()

        val safeRequest = request ?: return null
        val response = YamiboRetrofit.proxyWebViewResource(safeRequest) ?: return null

        val url = safeRequest.url.toString()
        if (!isExpectedStaticMime(url, response.mimeType)) {
            mimeRejectCount.incrementAndGet()
            closeWebResourceResponseQuietly(response)
            return null
        }

        successCount.incrementAndGet()
        return response
    }

}
