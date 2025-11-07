package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.CookieUtil

open class YamiboWebViewClient : WebViewClient() {

    private val jsCommand = """
        let bottomBar = document.getElementsByClassName("foot flex-box")[0]
        if (!bottomBar.classList.contains("foot_reply")) {
            bottomBar.style.display = "none";
            document.getElementsByClassName("foot_height")[0].style.display = "none";
        }
    """.trimMargin()

    private var defaultCookie = ""

    init {
        CookieUtil.getCookie { defaultCookie = it }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        GlobalData.loading = true
        CookieManager.getInstance().setCookie(url, defaultCookie)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        url?.let {
            if (it.contains(RequestConfig.BASE_URL)) {
                view?.evaluateJavascript(jsCommand, null)
                val cookieManager = CookieManager.getInstance()
                val cookie = cookieManager.getCookie(url)
                CookieUtil.saveCookie(cookie)
                GlobalData.cookie = cookie
            }
        }
        GlobalData.loading = false
        super.onPageFinished(view, url)
    }
}