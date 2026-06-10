package org.shirakawatyu.yamibo.novel.module

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
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

        class AttachmentNameBridge {
            @JavascriptInterface
            fun setAttachmentName(url: String, name: String) {
                val key = attachmentKey(url) ?: return
                pendingNames[key] = name
            }
        }

        fun setupDownloadListener(webView: WebView) {
            webView.addJavascriptInterface(AttachmentNameBridge(), "__yamiboAttach")
            webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val aid = Uri.parse(url).getQueryParameter("aid")
                    val attachmentId = normalizeAttachmentAid(aid)
                    val guessed = URLUtil.guessFileName(
                        url,
                        contentDisposition,
                        mimeType?.takeIf { it.isNotBlank() } ?: "text/plain"
                    )
                    val ext = mimeType?.let {
                        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                    }?.takeIf { it.isNotEmpty() }
                    val fileName = when {
                        !guessed.equals("forum.php", ignoreCase = true) &&
                                !guessed.equals("forum.php.txt", ignoreCase = true) -> guessed
                        aid != null -> attachmentId?.let { pendingNames.remove(it) }?.let { name ->
                                if (name.contains('.') && name.lastIndexOf('.') > name.lastIndexOf('/')) name
                                else ext?.let { "$name.$it" } ?: name
                            }
                            ?: "yamibo_${attachmentId ?: aid}" + (ext?.let { ".$it" } ?: "")
                        else -> "yamibo_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
                    }
                    DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType?.takeIf { it.isNotBlank() } ?: "text/plain")
                        addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                        addRequestHeader("Referer", webView.url ?: "https://bbs.yamibo.com/")
                        addRequestHeader("Accept", "text/plain,application/octet-stream,*/*")
                        setTitle(fileName)
                        setDescription("正在下载附件")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }.let {
                        val dm = webView.context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(it)
                    }
                } catch (_: Exception) {}
            }
        }

        private val ATTACH_INTERCEPT_JS = """
            (function() {
                function rememberAttachment(link) {
                    if (!link || !window.__yamiboAttach) return;
                    var span = link.querySelector('.link.f_b');
                    var name = span && span.textContent ? span.textContent.trim() : '';
                    if (name) window.__yamiboAttach.setAttachmentName(link.href, name);
                }

                // 页面完成后先缓存一次，避免点击与 DownloadListener 回调之间的时序竞争。
                document.querySelectorAll('a[href*="mod=attachment"]').forEach(rememberAttachment);

                if (window.__yamiboAttachHooked) return;
                window.__yamiboAttachHooked = true;
                document.addEventListener('click', function(e) {
                    var target = e.target;
                    var link = target && target.closest
                        ? target.closest('a[href*="mod=attachment"]')
                        : null;
                    rememberAttachment(link);
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