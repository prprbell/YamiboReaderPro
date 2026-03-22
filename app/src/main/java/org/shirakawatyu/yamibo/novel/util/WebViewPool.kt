package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewPool {
    private val pool = mutableListOf<WebView>()

    // 保留1个实例足够应付阅读页的进入了
    private const val MAX_POOL_SIZE = 1

    /**
     * 初始化预加载，放在主线程空闲时执行
     */
    fun init(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (pool.isEmpty()) {
            pool.add(createWebView(context))
        }
    }

    /**
     * 获取 WebView
     */
    fun acquire(context: Context): WebView {
        return if (pool.isNotEmpty()) {
            pool.removeAt(0)
        } else {
            createWebView(context)
        }
    }

    /**
     * 归还 WebView：清理页面痕迹，防止内存泄漏和数据串号
     */
    fun release(webView: WebView) {
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearFormData()
            onPause()
            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)

            // 剥离业务相关的 Client
            webViewClient = WebViewClient()
            webChromeClient = null
            settings.apply {
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
        }

        if (pool.size < MAX_POOL_SIZE) {
            pool.add(webView)
        } else {
            // 池子满了，直接销毁
            webView.destroy()
        }
    }

    /**
     * 创建干净的基础 WebView，使用 ApplicationContext
     */
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