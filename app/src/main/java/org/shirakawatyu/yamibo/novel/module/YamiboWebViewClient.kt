package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.CookieUtil

open class YamiboWebViewClient : WebViewClient() {

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

        // 隐藏顶部栏
        if (url.contains("home.php") || url.contains("mod=space")) {
            val keepTopBarList = listOf("do=pm", "do=blog", "do=thread", "do=favorite", "do=friend")

            if (keepTopBarList.none { url.contains(it) }) {
                css += " .my, .mz { visibility: hidden !important; pointer-events: none !important; }"
            }
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
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        applyHideCss(view, url)
    }
}