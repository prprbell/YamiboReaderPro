package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewPool {
    private val pool = mutableListOf<WebView>()
    private const val MAX_POOL_SIZE = 4

    fun init(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        while (pool.size < MAX_POOL_SIZE) {
            val webView = createWebView(context)
            webView.tag = "recycled_standby"
            pool.add(webView)
        }
    }

    fun acquire(context: Context): WebView {
        val webView = if (pool.isNotEmpty()) {
            pool.removeAt(0)
        } else {
            createWebView(context)
        }

        webView.onResume()
        webView.resumeTimers()

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
                loadsImagesAutomatically = true
                blockNetworkImage = false
            }

            evaluateJavascript("try { document.body.innerHTML=''; } catch(e){}", null)

            clearHistory()
            clearFormData()

            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)
        }

        if (pool.size < MAX_POOL_SIZE) {
            webView.tag = "recycled_standby"
            pool.add(webView)
        } else {
            webView.destroy()
        }
    }

    private fun createWebView(context: Context): WebView {
        return WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                domStorageEnabled = true
            }
        }
    }
}