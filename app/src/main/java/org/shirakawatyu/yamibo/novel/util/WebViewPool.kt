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
            if (pool.isEmpty()) {
                webView.tag = "recycled_awake"
            } else {
                webView.onPause()
                webView.tag = "recycled_paused"
            }
            pool.add(webView)
        }
    }

    fun acquire(context: Context): WebView {
        val webView = if (pool.isNotEmpty()) {
            // 优先取出池子里的第一个
            pool.removeAt(0)
        } else {
            createWebView(context)
        }

        if (pool.isNotEmpty()) {
            val standbyWebView = pool[0]
            if (standbyWebView.tag == "recycled_paused" || standbyWebView.tag == "recycled") {
                standbyWebView.onResume()
                standbyWebView.resumeTimers()
                standbyWebView.tag = "recycled_awake"
            }
        }

        webView.onResume()
        webView.resumeTimers()

        return webView
    }

    fun release(webView: WebView) {
        webView.apply {
            stopLoading()

            evaluateJavascript("document.open();document.close();", null)

            clearHistory()
            clearFormData()

            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)

            removeJavascriptInterface("ProberApi")
            removeJavascriptInterface("AndroidFullscreen")
            removeJavascriptInterface("NativeMangaApi")

            webViewClient = WebViewClient()
            webChromeClient = null
            settings.apply {
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
        }

        if (pool.size < MAX_POOL_SIZE) {
            if (pool.isEmpty()) {
                webView.onResume()
                webView.resumeTimers()
                webView.tag = "recycled_awake"
                pool.add(0, webView)
            } else {
                webView.onPause()
                webView.tag = "recycled_paused"
                pool.add(webView)
            }
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
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
        }
    }
}