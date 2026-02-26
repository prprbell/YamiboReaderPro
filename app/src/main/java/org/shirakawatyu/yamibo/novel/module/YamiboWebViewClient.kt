package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.CookieUtil

open class YamiboWebViewClient : WebViewClient() {
    // 隐藏底部导航栏
    private val jsCommand = """
        let bottomBar = document.getElementsByClassName("foot flex-box")[0]
        if (!bottomBar.classList.contains("foot_reply")) {
            bottomBar.style.display = "none";
            document.getElementsByClassName("foot_height")[0].style.display = "none";
        }
    """.trimIndent()

    // 移除MinePage顶部到首页的导航
    private val hideCssCommand = """
        javascript:(function() {
            var style = document.createElement('style');
            style.innerHTML = '.foot.flex-box:not(.foot_reply) { display: none !important; } .foot_height { display: none !important; }';
            document.head.appendChild(style);
        })()
    """.trimIndent()
    private var currentCookie = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            currentCookie = GlobalData.cookieFlow.first()
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        CookieManager.getInstance().setCookie(url, currentCookie)
        CookieManager.getInstance().flush()
        // 在页面开始加载时就注入 CSS
        view?.loadUrl(hideCssCommand)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        url?.let {
            if (it.contains(RequestConfig.BASE_URL)) {
                view?.evaluateJavascript(jsCommand, null)
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
                
                var uiObserver = null;

                function startUiObserver() {
                    var pswp = document.querySelector('.pswp');
                    if (!pswp) {
                        setTimeout(startUiObserver, 50);
                        return;
                    }

                    // 判断pswp--ui-visible
                    function notifyState() {
                        var isVisible = pswp.classList.contains('pswp--ui-visible');
                        
                        if (window.__lastUiState !== isVisible) {
                            window.__lastUiState = isVisible;
                            if (window.AndroidFullscreen) {
                                window.AndroidFullscreen.notifyUi(isVisible);
                            }
                        }
                    }

                    if (uiObserver) {
                        uiObserver.disconnect();
                    }

                    uiObserver = new MutationObserver(function(mutations) {
                        notifyState();
                    });

                    // 监听pswp容器的'class'属性变化
                    uiObserver.observe(pswp, { attributes: true, attributeFilter: ['class'] });
                    
                    notifyState();
                }

                function stopUiObserver() {
                    if (uiObserver) {
                        uiObserver.disconnect();
                        uiObserver = null;
                    }
                }

                function checkState() {
                    var state = window.history.state;
                    var isFullscreen = state && typeof state === 'object' && 'pswp_index' in state;
                    
                    if (window.AndroidFullscreen) {
                        window.AndroidFullscreen.notify(!!isFullscreen);
                    }
                    
                    if (isFullscreen) {
                        window.__lastUiState = null; 
                        startUiObserver();
                    } else {
                        stopUiObserver();
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

    /**
     * 处理加载失败
     */
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
        }
        super.onReceivedError(view, request, error)
    }

    /**
     * 处理 HTTP 错误
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame == true) {
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }
}