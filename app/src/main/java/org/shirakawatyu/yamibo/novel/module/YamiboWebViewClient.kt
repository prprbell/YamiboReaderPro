package org.shirakawatyu.yamibo.novel.module

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri

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

open class YamiboWebViewClient : WebViewClient() {

    companion object {
        private val pendingNames = ConcurrentHashMap<String, String>()
        private val mainHandler = Handler(Looper.getMainLooper())

        // WebView 在附件响应重定向、快速重复点击或部分机型的下载交接过程中，
        // 偶尔会在极短时间内回调两次 DownloadListener。
        // 使用稳定 aid（无 aid 时退回完整 URL）做短时间去重，避免 DownloadManager 入队两次。
        private const val DOWNLOAD_DEBOUNCE_MS = 3_000L
        private const val DOWNLOAD_RECORD_TTL_MS = 60_000L
        private val recentDownloadRequests = ConcurrentHashMap<String, Long>()
        private val downloadRequestLock = Any()

        /**
         * Discuz 附件下载 URL 不应该被主题 HTML 代理接管。
         *
         * 附件必须交还给 WebView 的真实下载流程，这样 DownloadListener 才能获得
         * 服务端返回的 Content-Disposition、MIME 和最终下载 URL。
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

        /**
         * Discuz 的 aid 通常是带签名和时间戳的 Base64：
         *
         * 1264306|8b054faf|1781066499|655106|547818
         *
         * 同一个附件重新进入页面后签名可能变化，所以缓存时只使用稳定的附件编号。
         */
        private fun normalizeAttachmentAid(aid: String?): String? {
            if (aid.isNullOrBlank()) return null

            val cleaned = aid.replace(' ', '+')

            return try {
                String(
                    Base64.decode(cleaned, Base64.DEFAULT),
                    Charsets.UTF_8
                )
                    .substringBefore('|')
                    .takeIf { it.isNotBlank() }
                    ?: cleaned
            } catch (_: IllegalArgumentException) {
                cleaned
            }
        }

        private fun attachmentKey(url: String): String? {
            return runCatching {
                normalizeAttachmentAid(
                    Uri.parse(url).getQueryParameter("aid")
                )
            }.getOrNull()
        }

        private fun downloadRequestKey(url: String): String {
            return attachmentKey(url)?.let { "aid:$it" }
                ?: runCatching {
                    // fragment 不参与 HTTP 请求，去掉后可避免同一资源被误判为不同下载。
                    Uri.parse(url)
                        .buildUpon()
                        .fragment(null)
                        .build()
                        .toString()
                }.getOrDefault(url)
        }

        private fun tryAcquireDownloadRequest(url: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            val key = downloadRequestKey(url)

            val accepted = synchronized(downloadRequestLock) {
                val previous = recentDownloadRequests[key]
                if (previous != null && now - previous < DOWNLOAD_DEBOUNCE_MS) {
                    false
                } else {
                    recentDownloadRequests[key] = now
                    true
                }
            }

            // 防止长期使用后记录无限增长；条件删除不会误删刚更新的值。
            if (recentDownloadRequests.size > 64) {
                recentDownloadRequests.forEach { (recordKey, timestamp) ->
                    if (now - timestamp > DOWNLOAD_RECORD_TTL_MS) {
                        recentDownloadRequests.remove(recordKey, timestamp)
                    }
                }
            }

            return accepted
        }

        class AttachmentNameBridge {
            @JavascriptInterface
            fun setAttachmentName(url: String, name: String) {
                val key = attachmentKey(url) ?: return
                val cleanName = cleanAttachmentName(name) ?: return

                pendingNames[key] = cleanName
            }
        }

        fun setupDownloadListener(webView: WebView) {
            webView.addJavascriptInterface(
                AttachmentNameBridge(),
                "__yamiboAttach"
            )

            webView.setDownloadListener {
                    url,
                    userAgent,
                    contentDisposition,
                    mimeType,
                    _ ->

                if (url.isNullOrBlank()) {
                    return@setDownloadListener
                }

                // 必须在 post 之前去重，否则两个回调都会进入主线程队列并各自 enqueue。
                if (!tryAcquireDownloadRequest(url)) {
                    return@setDownloadListener
                }

                mainHandler.post {
                    startAttachmentDownload(
                        webView = webView,
                        url = url,
                        userAgent = userAgent
                            ?: webView.settings.userAgentString,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType?.takeIf { it.isNotBlank() }
                    )
                }
            }
        }

        private fun startAttachmentDownload(
            webView: WebView,
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?
        ) {
            try {
                val aid = runCatching {
                    url.toUri().getQueryParameter("aid")
                }.getOrNull()

                val attachmentId = normalizeAttachmentAid(aid)

                val ext = mimeType
                    ?.let {
                        MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(it)
                    }
                    ?.takeIf { it.isNotEmpty() }

                val guessed = URLUtil.guessFileName(
                    url,
                    contentDisposition,
                    mimeType?.takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                )

                // 服务端 Content-Disposition 优先
                val cdFileName = parseContentDisposition(
                    contentDisposition,
                    mimeType
                )

                val fileName = when {
                    cdFileName != null -> cdFileName

                    !guessed.equals(
                        "forum.php",
                        ignoreCase = true
                    ) &&
                            !guessed.equals(
                                "forum.php.txt",
                                ignoreCase = true
                            ) -> guessed

                    aid != null -> {
                        attachmentId
                            ?.let { pendingNames.remove(it) }
                            ?.let { name ->
                                if (
                                    name.contains('.') &&
                                    name.lastIndexOf('.') >
                                    name.lastIndexOf('/')
                                ) {
                                    name
                                } else {
                                    ext
                                        ?.let { "$name.$it" }
                                        ?: name
                                }
                            }
                            ?: (
                                    "yamibo_" +
                                            (attachmentId ?: aid) +
                                            (ext?.let { ".$it" } ?: "")
                                    )
                    }

                    else -> (
                            "yamibo_" +
                                    System.currentTimeMillis() +
                                    (ext?.let { ".$it" } ?: "")
                            )
                }

                DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(
                        mimeType?.takeIf { it.isNotBlank() }
                            ?: "application/octet-stream"
                    )
                    addRequestHeader(
                        "Cookie",
                        CookieManager.getInstance()
                            .getCookie(url).orEmpty()
                    )
                    addRequestHeader(
                        "User-Agent",
                        userAgent
                            ?: webView.settings.userAgentString
                    )
                    addRequestHeader(
                        "Referer",
                        webView.url
                            ?: "https://bbs.yamibo.com/"
                    )
                    addRequestHeader(
                        "Accept",
                        "application/octet-stream,*/*"
                    )
                    setTitle(fileName)
                    setDescription("正在下载附件")
                    setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                }.let {
                    val dm =
                        webView.context.getSystemService(
                            Context.DOWNLOAD_SERVICE
                        ) as DownloadManager
                    dm.enqueue(it)
                }
            } catch (_: Throwable) { }
        }

        private fun parseContentDisposition(
            contentDisposition: String?,
            mimeType: String?
        ): String? {
            if (contentDisposition.isNullOrBlank()) return null

            /*
             * RFC 5987 / RFC 6266:
             * filename*=UTF-8''%E6%B5%8B%E8%AF%95.zip
             */
            val encodedMatch = Regex(
                """filename\*\s*=\s*([^']*)''([^;]+)""",
                RegexOption.IGNORE_CASE
            ).find(contentDisposition)

            if (encodedMatch != null) {
                val charset = encodedMatch.groupValues
                    .getOrNull(1)?.trim()?.ifBlank { "UTF-8" }
                    ?: "UTF-8"
                val encoded = encodedMatch.groupValues
                    .getOrNull(2)?.trim()?.trim('"')

                if (!encoded.isNullOrBlank()) {
                    val decoded = runCatching {
                        URLDecoder.decode(encoded, charset)
                    }.getOrElse {
                        runCatching {
                            URLDecoder.decode(encoded, "UTF-8")
                        }.getOrDefault(encoded)
                    }
                    return ensureExtension(decoded, mimeType)
                }
            }

            /*
             * filename="example.zip" / filename=example.zip
             */
            val filename = Regex(
                """filename\s*=\s*(?:"([^"]+)"|([^;]+))""",
                RegexOption.IGNORE_CASE
            ).find(contentDisposition)
                ?.let { match ->
                    match.groupValues.drop(1)
                        .firstOrNull { it.isNotBlank() }
                }
                ?.trim()
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }

            return filename?.let { ensureExtension(it, mimeType) }
        }

        private fun ensureExtension(name: String, mimeType: String?): String {
            val clean = name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .trimEnd('.')

            val ext = mimeType?.let {
                MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(it)
            }?.takeIf { it.isNotEmpty() } ?: return clean

            val hasExt = clean.substringAfterLast('.', "")
                .isNotBlank()

            return if (hasExt) clean else "$clean.$ext"
        }

        private fun cleanAttachmentName(name: String?): String? {
            val cleaned = name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            return cleaned
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .trimEnd('.')
                .ifBlank { null }
        }

        private val ATTACH_INTERCEPT_JS = """
            (function() {
                function rememberAttachment(link, name) {
                    if (!link || !window.__yamiboAttach) return;
                    if (!name) {
                        var span = link.querySelector('.link.f_b');
                        name = span && span.textContent ? span.textContent.trim() : '';
                    }
                    if (!name) {
                        var selectors = [
                            '.attnm', '.attachname', '.filename', '.file-name',
                            'strong', 'b', 'em', 'span'
                        ];
                        for (var i = 0; i < selectors.length; i++) {
                            var node = link.querySelector(selectors[i]);
                            var text = node && node.textContent ? node.textContent.trim() : '';
                            if (text && /\.[a-z0-9]{1,16}$/i.test(text)) { name = text; break; }
                        }
                    }
                    if (!name) name = (link.textContent || '').replace(/[\\r\\n\\t]+/g, ' ').replace(/\\s+/g, ' ').trim();
                    if (name) window.__yamiboAttach.setAttachmentName(link.href, name);
                }

                document.querySelectorAll('a[href*="mod=attachment"]').forEach(function(link) {
                    rememberAttachment(link);
                    link.removeAttribute('target');
                });

                if (window.__yamiboAttachHookedV2) return;
                window.__yamiboAttachHookedV2 = true;

                ['pointerdown', 'touchstart', 'mousedown'].forEach(function(type) {
                    document.addEventListener(type, function(e) {
                        var link = e.target && e.target.closest
                            ? e.target.closest('a[href*="mod=attachment"]')
                            : null;
                        if (!link) return;
                        rememberAttachment(link);
                        e.stopPropagation();
                        if (e.stopImmediatePropagation) {
                            e.stopImmediatePropagation();
                        }
                    }, true);
                });

                document.addEventListener('click', function(e) {
                    var link = e.target && e.target.closest
                        ? e.target.closest('a[href*="mod=attachment"]')
                        : null;
                    if (!link) return;
                    rememberAttachment(link);
                    link.removeAttribute('target');
                    e.preventDefault();
                    e.stopPropagation();
                    if (e.stopImmediatePropagation) {
                        e.stopImmediatePropagation();
                    }
                    setTimeout(function() {
                        window.location.href = link.href;
                    }, 0);
                }, true);
            })();
        """.trimIndent()
    }

    private var currentCookie = ""

    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob()
    )

    init {
        scope.launch {
            currentCookie = GlobalData.cookieFlow.first()
        }
    }

    protected fun applyHideCss(
        view: WebView?,
        currentUrl: String?
    ) {
        val url = currentUrl ?: view?.url.orEmpty()

        var css =
            ".foot.flex-box:not(.foot_reply) " +
                    "{ display: none !important; } " +
                    ".foot_height " +
                    "{ display: none !important; }"

        if (GlobalData.isDarkMode.value) {
            css += """
                /* 只给真正的帖子列表项添加按压反馈。
                   不再匹配 .bm_c li，避免附件所在的大父容器
                   因 :active + filter 被整块压暗，并在下载交接时出现按压态残留。

                   暗色主题会给标题、摘要、顶部用户区等子节点设置不透明背景，
                   所以不能只改 li.list 自身背景；要在按压时作用到真实可见的直接子块。
                   这里不使用 ::after 或真实覆盖层，避免改变 WebView hit-testing。 */
                .threadlist li.list,
                #threadlist li.list,
                .forumlist li.list,
                .threadlist .thread-item,
                #threadlist .thread-item {
                    transition: background-color 90ms ease !important;
                    -webkit-tap-highlight-color:
                        rgba(255, 255, 255, 0.06) !important;
                }

                .threadlist li.list.yamibo-thread-pressed,
                #threadlist li.list.yamibo-thread-pressed,
                .forumlist li.list.yamibo-thread-pressed,
                .threadlist .thread-item.yamibo-thread-pressed,
                #threadlist .thread-item.yamibo-thread-pressed,
                .threadlist li.list:active,
                #threadlist li.list:active,
                .forumlist li.list:active,
                .threadlist .thread-item:active,
                #threadlist .thread-item:active {
                    filter: none !important;
                    background-color:
                        rgba(255, 255, 255, 0.045) !important;
                }

                .threadlist li.list.yamibo-thread-pressed > *,
                #threadlist li.list.yamibo-thread-pressed > *,
                .forumlist li.list.yamibo-thread-pressed > *,
                .threadlist .thread-item.yamibo-thread-pressed > *,
                #threadlist .thread-item.yamibo-thread-pressed > *,
                .threadlist li.list:active > *,
                #threadlist li.list:active > *,
                .forumlist li.list:active > *,
                .threadlist .thread-item:active > *,
                #threadlist .thread-item:active > * {
                    filter: brightness(1.08) !important;
                    transition: filter 90ms ease !important;
                }

                /* 附件链接只改变自身按压背景，禁止 filter/opacity 影响祖先合成层。 */
                a[href*="mod=attachment"] {
                    -webkit-tap-highlight-color:
                        rgba(255, 255, 255, 0.08) !important;
                    transition: background-color 90ms ease !important;
                }

                a[href*="mod=attachment"]:active {
                    filter: none !important;
                    opacity: 1 !important;
                    background-color:
                        rgba(255, 255, 255, 0.06) !important;
                }
            """.trimIndent()
        }

        if (url.contains("mycenter=1")) {
            css +=
                " .my, .mz " +
                        "{ visibility: hidden !important; " +
                        "pointer-events: none !important; }"
        }

        val safeCss = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val injectJs = """
            (function() {
                var style = document.getElementById(
                    'yamibo-hide-style'
                );

                if (!style) {
                    style = document.createElement('style');
                    style.id = 'yamibo-hide-style';

                    var tryInject = function() {
                        if (document.head) {
                            document.head.appendChild(style);
                            return true;
                        }

                        if (document.documentElement) {
                            document.documentElement
                                .appendChild(style);
                            return true;
                        }

                        return false;
                    };

                    if (!tryInject()) {
                        var observer = new MutationObserver(
                            function(mutations, obs) {
                                if (tryInject()) {
                                    obs.disconnect();
                                }
                            }
                        );

                        observer.observe(
                            document,
                            {
                                childList: true,
                                subtree: true
                            }
                        );
                    }
                }

                style.innerHTML = '$safeCss';
            })();
        """.trimIndent()

        view?.evaluateJavascript(injectJs, null)
    }

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?
    ) {
        val targetCookie = currentCookie.ifBlank {
            GlobalData.currentCookie
        }

        if (
            url != null &&
            targetCookie.isNotBlank()
        ) {
            CookieManager.getInstance().setCookie(
                url,
                targetCookie
            )
            CookieManager.getInstance().flush()
        }

        if (
            url?.startsWith(RequestConfig.BASE_URL) == true
        ) {
            applyHideCss(view, url)
        }

        super.onPageStarted(view, url, favicon)
    }

    override fun onPageCommitVisible(
        view: WebView?,
        url: String?
    ) {
        applyHideCss(view, url)
        super.onPageCommitVisible(view, url)
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?
    ) {
        url?.let { currentUrl ->
            if (
                currentUrl.contains(
                    RequestConfig.BASE_URL
                )
            ) {
                applyHideCss(view, currentUrl)

                val cookieManager =
                    CookieManager.getInstance()

                val cookie =
                    cookieManager.getCookie(currentUrl)

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

                        var isFullscreen =
                            state &&
                            typeof state === 'object' &&
                            'pswp_index' in state;

                        if (window.AndroidFullscreen) {
                            window.AndroidFullscreen.notify(
                                !!isFullscreen
                            );
                        }
                    }

                    var originalPushState =
                        history.pushState;

                    history.pushState = function() {
                        var result =
                            originalPushState.apply(
                                this,
                                arguments
                            );

                        checkState();
                        return result;
                    };

                    var originalReplaceState =
                        history.replaceState;

                    history.replaceState = function() {
                        var result =
                            originalReplaceState.apply(
                                this,
                                arguments
                            );

                        checkState();
                        return result;
                    };

                    window.addEventListener(
                        'popstate',
                        function() {
                            checkState();
                        }
                    );

                    checkState();
                })();
            """.trimIndent(),
            null
        )

        view?.evaluateJavascript(
            ATTACH_INTERCEPT_JS,
            null
        )
    }

    override fun doUpdateVisitedHistory(
        view: WebView?,
        url: String?,
        isReload: Boolean
    ) {
        super.doUpdateVisitedHistory(
            view,
            url,
            isReload
        )

        applyHideCss(view, url)
    }
}
