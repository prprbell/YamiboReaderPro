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
import java.net.URLDecoder
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

            // 新版会在 DownloadListener 之前由 JS 直接拉起下载器选择框，
            // 因此页面真实文件名和缓存文件名必须优先于 forum.php 的 URL 推断。
            cleanAttachmentName(pageFileName)?.let { return ensureExtension(it, ext) }

            attachmentId?.let { pendingNames.remove(it) }
                ?.let(::cleanAttachmentName)
                ?.let { return ensureExtension(it, ext) }

            parseContentDispositionFileName(contentDisposition)
                ?.let(::cleanAttachmentName)
                ?.let { return ensureExtension(it, ext) }

            val guessed = URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            )
            if (!isGenericAttachmentName(guessed)) {
                return ensureExtension(sanitizeFileName(guessed), ext)
            }

            if (aid != null) {
                return "yamibo_${attachmentId ?: aid}" + (ext?.let { ".$it" } ?: "")
            }

            return "yamibo_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
        }

        private fun ensureExtension(name: String, extension: String?): String {
            val clean = sanitizeFileName(name)
            val hasExtension = clean.substringAfterLast('.', "").isNotBlank()
            return if (hasExtension || extension.isNullOrBlank()) clean else "$clean.$extension"
        }

        private fun isGenericAttachmentName(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower == "forum.php" ||
                    lower == "forum.php.txt" ||
                    lower == "forum.php.bin" ||
                    lower == "download" ||
                    lower == "attachment" ||
                    lower.startsWith("forum.php?")
        }

        private fun parseContentDispositionFileName(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) return null

            val utf8 = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('"')
            if (!utf8.isNullOrBlank()) {
                return runCatching { URLDecoder.decode(utf8, "UTF-8") }.getOrDefault(utf8)
            }

            return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
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
                function cleanCandidate(value) {
                    if (!value) return '';
                    var text = String(value)
                        .replace(/[\\r\\n\\t]+/g, ' ')
                        .replace(/\\s+/g, ' ')
                        .trim();
                    text = text
                        .replace(/^附件[:：]?\\s*/i, '')
                        .replace(/\\s*\\([^)]*(?:KB|MB|GB|字节|下载次数)[^)]*\\)\\s*$/i, '')
                        .replace(/\\s+(?:下载次数|售价|阅读权限)[:：].*$/i, '')
                        .trim();
                    return text;
                }

                function fileLike(value) {
                    var text = cleanCandidate(value);
                    return /\\.[a-z0-9]{1,10}$/i.test(text) ? text : '';
                }

                function attachmentName(link) {
                    if (!link) return '';

                    var attributes = [
                        link.getAttribute('download'),
                        link.getAttribute('data-filename'),
                        link.getAttribute('data-file-name'),
                        link.getAttribute('title'),
                        link.getAttribute('aria-label')
                    ];
                    for (var i = 0; i < attributes.length; i++) {
                        var fromAttr = fileLike(attributes[i]);
                        if (fromAttr) return fromAttr;
                    }

                    var selectors = [
                        '.attnm', '.attachname', '.filename', '.file-name',
                        '.link.f_b', 'strong', 'b', 'em', 'span'
                    ];
                    for (var j = 0; j < selectors.length; j++) {
                        var node = link.querySelector(selectors[j]);
                        var fromChild = node ? fileLike(node.textContent) : '';
                        if (fromChild) return fromChild;
                    }

                    var ownText = fileLike(link.textContent);
                    if (ownText) return ownText;

                    var container = link.closest
                        ? link.closest('.attnm, .attach, .attachment, .t_attach, .pcb, .plc')
                        : null;
                    if (container) {
                        var nodes = container.querySelectorAll(
                            '.attnm, .attachname, .filename, .file-name, .link.f_b, strong, b, em, span, a'
                        );
                        for (var k = 0; k < nodes.length; k++) {
                            var fromContainer = fileLike(nodes[k].textContent);
                            if (fromContainer) return fromContainer;
                        }
                    }

                    return cleanCandidate(link.textContent);
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

        if (GlobalData.isDarkMode.value) {
            css += """
                .threadlist li, #threadlist li, .bm_c li, .forumlist li,
                .threadlist .thread-item, #threadlist .thread-item {
                    transition: filter 90ms ease, background-color 90ms ease !important;
                    -webkit-tap-highlight-color: rgba(0, 0, 0, 0.18) !important;
                }
                .threadlist li:active, #threadlist li:active, .bm_c li:active, .forumlist li:active,
                .threadlist .thread-item:active, #threadlist .thread-item:active {
                    filter: brightness(0.72) !important;
                    background-color: rgba(0, 0, 0, 0.22) !important;
                }
            """.trimIndent()
        }

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
