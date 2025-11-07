package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import org.shirakawatyu.yamibo.novel.global.GlobalData

class PassageWebViewClient(val onFinished: (html: String, url: String?) -> Unit): YamiboWebViewClient() {

    private val logTag = "PassageWebViewClient"

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) {
            Log.i(logTag, url)
        }
        GlobalData.loading = true
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        view?.evaluateJavascript("document.getElementsByClassName('viewthread')[0].innerHTML") {
            if (url != null && !url.contains("authorid")) {
                view.evaluateJavascript("document.getElementsByClassName('nav-more-item')[0].click()") {}
            } else {
                var innerHTML = it.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\n", "\n")
                innerHTML = "<html>${innerHTML.substring(1, innerHTML.length - 1)}</html>"
                GlobalData.loading = false
                onFinished(innerHTML, url)
            }
            val cookie = CookieManager.getInstance().getCookie(url)
            GlobalData.cookie = cookie
        }

        super.onPageFinished(view, url)
    }
}