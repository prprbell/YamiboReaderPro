package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.AttachmentDownloadUtil
import androidx.core.net.toUri

open class YamiboWebViewClient : WebViewClient() {

    companion object {
        private val pendingNames = ConcurrentHashMap<String, String>()
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * 这些 URL 是 Discuz 附件下载，不应该被夜间模式 HTML 代理接管。
         * 否则 dark-mode 下主框架请求会被当成 HTML 抓取并注入 CSS，下载链路容易卡住。
         */
        fun isAttachmentDownloadUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("mod=attachment") ||
                    lower.contains("forum.php?mod=attachment") ||
                    lower.contains("forum.php&mod=attachment")
        }

        fun shouldProxyHtmlForTheme(url: String?, accept: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (isAttachmentDownloadUrl(url)) return false
            val lower = url.lowercase(Locale.ROOT)
            if (!lower.contains("bbs.yamibo.com")) return false
            val acceptValue = accept.orEmpty()
            return acceptValue.isBlank() ||
                    acceptValue.contains("text/html", ignoreCase = true) ||
                    acceptValue.contains("application/xhtml+xml", ignoreCase = true) ||
                    acceptValue.contains("*/*")
        }

        private fun normalizeAttachmentAid(aid: String?): String? {
            if (aid.isNullOrBlank()) return null

            // Discuz 的 aid 是带签名和时间戳的 Base64 字符串，例如：
            // 1264306|8b054faf|1781066499|655106|547818
            // 同一个附件在跳转或刷新后可能得到新的签名，因此只使用稳定的附件编号作缓存键。
            val cleaned = aid.replace(' ', '+')
            return try {
                String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
                    .substringBefore('|')
                    .takeIf { it.isNotBlank() }
                    ?: cleaned
            } catch (_: IllegalArgumentException) {
                cleaned
            }
        }

        private fun attachmentKey(url: String): String? {
            return normalizeAttachmentAid(Uri.parse(url).getQueryParameter("aid"))
        }

        class AttachmentNameBridge(webView: WebView) {
            private val webViewRef = WeakReference(webView)

            @JavascriptInterface
            fun setAttachmentName(url: String, name: String) {
                val key = attachmentKey(url) ?: return
                val cleanName = cleanAttachmentName(name) ?: return
                pendingNames[key] = cleanName
            }

            /**
             * JS 直接拦截附件点击并走原生下载面板。
             * 这样 dark-mode 的 shouldInterceptRequest 不会把附件主框架请求误当 HTML 代理，
             * 也避免 target=_blank / DownloadListener 在不同 WebView 内核上的不稳定触发。
             */
            @JavascriptInterface
            fun downloadAttachment(url: String, name: String?) {
                val cleanName = cleanAttachmentName(name)
                cleanName?.let { attachmentKey(url)?.let { key -> pendingNames[key] = it } }
                mainHandler.post {
                    val webView = webViewRef.get() ?: return@post
                    val absoluteUrl = toAbsoluteUrl(webView.url, url)
                    startAttachmentDownload(
                        webView = webView,
                        url = absoluteUrl,
                        userAgent = webView.settings.userAgentString,
                        contentDisposition = null,
                        mimeType = guessMimeType(absoluteUrl, cleanName),
                        pageFileName = cleanName
                    )
                }
            }
        }

        fun setupDownloadListener(webView: WebView) {
            webView.addJavascriptInterface(AttachmentNameBridge(webView), "__yamiboAttach")
            webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                mainHandler.post {
                    startAttachmentDownload(
                        webView = webView,
                        url = url,
                        userAgent = userAgent ?: webView.settings.userAgentString,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType?.takeIf { it.isNotBlank() },
                        pageFileName = null
                    )
                }
            }
        }

        private fun startAttachmentDownload(
            webView: WebView,
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            pageFileName: String?
        ) {
            try {
                val aid = url.toUri().getQueryParameter("aid")
                val attachmentId = normalizeAttachmentAid(aid)
                val fileName = chooseAttachmentFileName(
                    url = url,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    aid = aid,
                    attachmentId = attachmentId,
                    pageFileName = pageFileName
                )

                AttachmentDownloadUtil.start(
                    context = webView.context,
                    request = AttachmentDownloadUtil.Request(
                        url = url,
                        fileName = fileName,
                        mimeType = mimeType?.takeIf { it.isNotBlank() },
                        userAgent = userAgent ?: webView.settings.userAgentString,
                        referer = webView.url ?: "https://bbs.yamibo.com/",
                        cookie = CookieManager.getInstance().getCookie(url) ?: "",
                        contentDisposition = contentDisposition
                    )
                )
            } catch (_: Exception) {}
        }

        private fun chooseAttachmentFileName(
            url: String,
            contentDisposition: String?,
            mimeType: String?,
            aid: String?,
            attachmentId: String?,
            pageFileName: String?
        ): String {
            val ext = mimeType?.let {
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            }?.takeIf { it.isNotEmpty() }

            pageFileName?.let { name ->
                return if (name.contains('.') && name.lastIndexOf('.') > name.lastIndexOf('/')) name
                else ext?.let { "$name.$it" } ?: name
            }

            val guessed = URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType?.takeIf { it.isNotBlank() } ?: "text/plain"
            )
            if (!guessed.equals("forum.php", ignoreCase = true) &&
                !guessed.equals("forum.php.txt", ignoreCase = true)
            ) {
                return sanitizeFileName(guessed)
            }

            if (aid != null) {
                attachmentId?.let { pendingNames.remove(it) }?.let { name ->
                    return if (name.contains('.') && name.lastIndexOf('.') > name.lastIndexOf('/')) name
                    else ext?.let { "$name.$it" } ?: name
                }
                return "yamibo_${attachmentId ?: aid}" + (ext?.let { ".$it" } ?: "")
            }

            return "yamibo_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
        }

        private fun cleanAttachmentName(name: String?): String? {
            val cleaned = name
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return sanitizeFileName(cleaned)
        }

        private fun sanitizeFileName(name: String): String {
            return name
                .replace(Regex("[\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "yamibo_${System.currentTimeMillis()}" }
        }

        private fun toAbsoluteUrl(baseUrl: String?, rawUrl: String): String {
            return try {
                val parsed = rawUrl.toUri()
                if (!parsed.scheme.isNullOrBlank()) rawUrl else URL(URL(baseUrl ?: "https://bbs.yamibo.com/"), rawUrl).toString()
            } catch (_: Exception) {
                rawUrl
            }
        }

        private fun guessMimeType(url: String, fileName: String?): String? {
            val extFromName = fileName
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
            val extFromUrl = android.webkit.MimeTypeMap.getFileExtensionFromUrl(url)
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
            val ext = extFromName ?: extFromUrl
            return ext?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        }

        private val ATTACH_INTERCEPT_JS = """
            (function() {
                function attachmentName(link) {
                    if (!link) return '';
                    var node = link.querySelector('.link.f_b, .attnm a, span, em');
                    var text = (node && node.textContent ? node.textContent : link.textContent) || '';
                    return text.replace(/\s+/g, ' ').trim();
                }

                function rememberAttachment(link) {
                    if (!link || !window.__yamiboAttach) return;
                    var name = attachmentName(link);
                    if (name) window.__yamiboAttach.setAttachmentName(link.href || link.getAttribute('href') || '', name);
                }

                function closestAttachmentLink(target) {
                    return target && target.closest
                        ? target.closest('a[href*="mod=attachment"]')
                        : null;
                }

                // 页面完成后先缓存一次，避免点击与 DownloadListener 回调之间的时序竞争。
                document.querySelectorAll('a[href*="mod=attachment"]').forEach(rememberAttachment);

                if (window.__yamiboAttachHooked) return;
                window.__yamiboAttachHooked = true;
                document.addEventListener('click', function(e) {
                    var link = closestAttachmentLink(e.target);
                    if (!link) return;

                    var href = link.href || link.getAttribute('href') || '';
                    var name = attachmentName(link);
                    rememberAttachment(link);

                    if (window.__yamiboAttach && typeof window.__yamiboAttach.downloadAttachment === 'function') {
                        e.preventDefault();
                        e.stopPropagation();
                        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                        window.__yamiboAttach.downloadAttachment(href, name);
                        return false;
                    }
                }, true);
            })();
        """.trimIndent()
    }

    private var currentCookie = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            currentCookie = GlobalData.cookieFlow.first()
        }
    }

    protected fun applyHideCss(view: WebView?, currentUrl: String?) {
        val url = currentUrl ?: view?.url ?: ""

        // 隐藏底部栏
        var css =
            ".foot.flex-box:not(.foot_reply) { display: none !important; } .foot_height { display: none !important; }"

        // 隐藏顶部栏（仅在自己的主页 mycenter=1 时生效）
        if (url.contains("mycenter=1")) {
            css += " .my, .mz { visibility: hidden !important; pointer-events: none !important; }"
        }

        val injectJs = """
            javascript:(function() {
                var style = document.getElementById('yamibo-hide-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'yamibo-hide-style';

                    var tryInject = function() {
                        if (document.head) {
                            document.head.appendChild(style);
                            return true;
                        } else if (document.documentElement) {
                            document.documentElement.appendChild(style);
                            return true;
                        }
                        return false;
                    };

                    if (!tryInject()) {
                        var observer = new MutationObserver(function(mutations, obs) {
                            if (tryInject()) {
                                obs.disconnect();
                            }
                        });
                        observer.observe(document, { childList: true, subtree: true });
                    }
                }
                style.innerHTML = '$css';
            })();
        """.trimIndent()

        view?.evaluateJavascript(injectJs, null)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        val targetCookie = currentCookie.ifBlank {
            GlobalData.currentCookie
        }
        if (url != null && targetCookie.isNotBlank()) {
            CookieManager.getInstance().setCookie(url, targetCookie)
            CookieManager.getInstance().flush()
        }

        if (url?.startsWith(RequestConfig.BASE_URL) == true) {
            applyHideCss(view, url)
        }

        super.onPageStarted(view, url, favicon)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        applyHideCss(view, url)
        super.onPageCommitVisible(view, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        url?.let {
            if (it.contains(RequestConfig.BASE_URL)) {
                applyHideCss(view, url)

                val cookieManager = CookieManager.getInstance()
                val cookie = cookieManager.getCookie(url)
                if (cookie != null) {
                    CookieUtil.saveCookie(cookie)
                    currentCookie = cookie
                }
            }
        }
        super.onPageFinished(view, url)

        view?.evaluateJavascript(
            """
            (function() {
                if (window.__historyHooked) return;
                window.__historyHooked = true;

                function checkState() {
                    var state = window.history.state;
                    var isFullscreen = state && typeof state === 'object' && 'pswp_index' in state;

                    if (window.AndroidFullscreen) {
                        window.AndroidFullscreen.notify(!!isFullscreen);
                    }
                }

                var originalPushState = history.pushState;
                history.pushState = function() {
                    var result = originalPushState.apply(this, arguments);
                    checkState();
                    return result;
                };

                var originalReplaceState = history.replaceState;
                history.replaceState = function() {
                    var result = originalReplaceState.apply(this, arguments);
                    checkState();
                    return result;
                };

                window.addEventListener('popstate', function() {
                    checkState();
                });

                checkState();
            })();
            """.trimIndent(), null
        )
        view?.evaluateJavascript(ATTACH_INTERCEPT_JS, null)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        applyHideCss(view, url)
    }
}
