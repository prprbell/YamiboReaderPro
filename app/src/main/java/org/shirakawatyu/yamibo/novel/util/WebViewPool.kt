package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

object WebViewPool {
    private val pool = ArrayList<WebView>()
    private val warmingUpSet = HashSet<WebView>()
    private val webViewUseCount = HashMap<WebView, Int>()

    private const val MAX_POOL_SIZE = 3
    private const val MAX_USES_PER_WEBVIEW = 8

    fun init(context: Context) {
        checkMainThread("init")
        while (pool.size + warmingUpSet.size < MAX_POOL_SIZE) {
            val webView = createWebView(context)
            webView.tag = "recycled_standby"
            pool.add(webView)
            webViewUseCount[webView] = 0
        }
    }

    fun deepWarmUp(activity: Activity) {
        checkMainThread("deepWarmUp")

        // 使用 removeFirstOrNull() 更优雅
        val webView = pool.removeFirstOrNull() ?: return
        warmingUpSet.add(webView)

        val decorView = activity.window.decorView as? ViewGroup
        val appContext = activity.applicationContext

        if (decorView == null) {
            rollbackWarmUp(webView, appContext)
            return
        }

        val layoutParams = FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = -10000
            topMargin = -10000
        }

        try {
            (webView.context as? MutableContextWrapper)?.baseContext = activity
            decorView.addView(webView, layoutParams)
            webView.loadDataWithBaseURL(
                "https://bbs.yamibo.com/",
                "<html><body></body></html>",
                "text/html",
                "utf-8",
                null
            )

            webView.postDelayed({
                try {
                    decorView.removeView(webView)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    rollbackWarmUp(webView, appContext)
                }
            }, 500)
        } catch (e: Exception) {
            e.printStackTrace()
            rollbackWarmUp(webView, appContext)
        }
    }

    private fun rollbackWarmUp(webView: WebView, appContext: Context) {
        (webView.context as? MutableContextWrapper)?.baseContext = appContext
        warmingUpSet.remove(webView)
        // 回收至可用池
        if (pool.size < MAX_POOL_SIZE) {
            pool.add(webView)
        } else {
            discard(webView)
        }
    }

    fun acquire(context: Context): WebView {
        checkMainThread("acquire")
        val webView = pool.removeFirstOrNull() ?: createWebView(context).also {
            webViewUseCount[it] = 0
        }

        webView.stopLoading()
        (webView.context as? MutableContextWrapper)?.baseContext = context

        val currentUses = webViewUseCount[webView] ?: 0
        webViewUseCount[webView] = currentUses + 1
        webView.onResume()

        return webView
    }

    fun release(webView: WebView) {
        checkMainThread("release")
        // 若从预热集合误入 release，直接阻断
        if (warmingUpSet.contains(webView)) return

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
            clearFormData()
            clearHistory()
            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            onPause()
        }

        val appContext = webView.context.applicationContext
        (webView.context as? MutableContextWrapper)?.baseContext = appContext

        val uses = webViewUseCount[webView] ?: 0
        if (uses >= MAX_USES_PER_WEBVIEW) {
            discard(webView)
        } else if (pool.size < MAX_POOL_SIZE) {
            webView.tag = "recycled_standby"
            if (!pool.contains(webView)) pool.add(webView)
        } else {
            discard(webView)
        }
    }

    fun discard(webView: WebView) {
        checkMainThread("discard")
        pool.remove(webView)
        warmingUpSet.remove(webView)
        webViewUseCount.remove(webView)
        try {
            webView.stopLoading()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkMainThread(methodName: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("WebViewPool.$methodName must be called on Main Thread!")
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
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        }
    }
}