package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewPool {
    private val pool = mutableListOf<WebView>()
    private const val MAX_POOL_SIZE = 4

    private val webViewUseCount = mutableMapOf<WebView, Int>()
    private const val MAX_USES_PER_WEBVIEW = 8

    fun init(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        while (pool.size < MAX_POOL_SIZE) {
            val webView = createWebView(context)
            webView.tag = "recycled_standby"
            pool.add(webView)
            webViewUseCount[webView] = 0
        }
    }

    fun acquire(context: Context): WebView {
        val webView = if (pool.isNotEmpty()) {
            pool.removeAt(0)
        } else {
            createWebView(context).also {
                webViewUseCount[it] = 0
            }
        }

        (webView.context as? MutableContextWrapper)?.baseContext = context

        val currentUses = webViewUseCount[webView] ?: 0
        webViewUseCount[webView] = currentUses + 1

        webView.onResume()

        return webView
    }

    fun release(webView: WebView) {
        webView.apply {
            stopLoading()
            removeJavascriptInterface("ProberApi")
            removeJavascriptInterface("AndroidFullscreen")
            removeJavascriptInterface("NativeMangaApi")
            webViewClient = WebViewClient()
            webChromeClient = null
            settings.apply {
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            clearHistory()
            clearFormData()
            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)
        }

        (webView.context as? MutableContextWrapper)?.baseContext =
            webView.context.applicationContext

        val uses = webViewUseCount[webView] ?: 0
        if (uses >= MAX_USES_PER_WEBVIEW) {
            webViewUseCount.remove(webView)
            webView.destroy()

            if (pool.size < MAX_POOL_SIZE) {
                val freshWebView = createWebView(webView.context)
                freshWebView.tag = "recycled_standby"
                pool.add(freshWebView)
                webViewUseCount[freshWebView] = 0
            }
        } else if (pool.size < MAX_POOL_SIZE) {
            webView.tag = "recycled_standby"
            pool.add(webView)
        } else {
            webViewUseCount.remove(webView)
            webView.destroy()
        }
    }

    private fun createWebView(context: Context): WebView {
        val contextWrapper = MutableContextWrapper(context.applicationContext)
        return WebView(contextWrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
        }
    }
}