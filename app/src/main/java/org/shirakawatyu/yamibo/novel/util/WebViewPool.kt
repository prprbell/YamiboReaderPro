package org.shirakawatyu.yamibo.novel.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import java.util.ArrayDeque
import java.util.IdentityHashMap

object WebViewPool {
    private const val TAG = "WebViewPool"

    private class WebViewHolder(
        val webView: WebView,
        val contextWrapper: MutableContextWrapper
    ) {
        var useCount = 0
        var isDirty = false
    }

    private val pool = ArrayDeque<WebViewHolder>()
    private val activeHolders = IdentityHashMap<WebView, WebViewHolder>()

    private const val MAX_POOL_SIZE = 3
    private const val MAX_USES_PER_WEBVIEW = 8
    private const val CLEANUP_DELAY_MS = 10 * 60 * 1000L

    private const val BLANK_HTML = "<html><body></body></html>"

    private val EMPTY_WEB_CLIENT = object : WebViewClient() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(
            view: WebView?,
            detail: android.webkit.RenderProcessGoneDetail?
        ): Boolean {
            Log.e(TAG, "Fatal: Render process gone. Crash: ${detail?.didCrash()}")
            mainHandler.post { view?.let { discard(it) } }
            return true
        }
    }
    private val EMPTY_CHROME_CLIENT = WebChromeClient()

    @Volatile private var isReplenishing = false
    @Volatile private var isCleaningDirty = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = Runnable { clearIdlePool() }

    fun init(context: Context) {
        checkMainThread("init")
        val appContext = context.applicationContext

        triggerAsyncReplenish(appContext)

        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    mainHandler.post { clearIdlePool() }
                }
            }
            override fun onLowMemory() {
                mainHandler.post { clearIdlePool() }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
    }

    private fun scheduleCleanup() {
        mainHandler.removeCallbacks(cleanupRunnable)
        mainHandler.postDelayed(cleanupRunnable, CLEANUP_DELAY_MS)
    }

    private fun clearIdlePool() {
        checkMainThread("clearIdlePool")
        while (pool.isNotEmpty()) {
            discardHolder(pool.removeFirst())
        }
    }

    private fun triggerAsyncReplenish(appContext: Context) {
        if (isReplenishing) return
        isReplenishing = true

        Looper.myQueue().addIdleHandler {
            try {
                if (pool.size + activeHolders.size < MAX_POOL_SIZE) {
                    val holder = createWebViewHolder(appContext)
                    holder.webView.tag = "recycled_standby"
                    pool.addLast(holder)
                    return@addIdleHandler true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async replenish failed", e)
            }
            isReplenishing = false
            false
        }
    }

    private fun triggerAsyncWash() {
        if (isCleaningDirty) return
        isCleaningDirty = true

        Looper.myQueue().addIdleHandler {
            try {
                val iterator = pool.iterator()
                while (iterator.hasNext()) {
                    val holder = iterator.next()
                    if (holder.isDirty) {
                        washWebView(holder.webView)
                        holder.isDirty = false
                        return@addIdleHandler true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async wash failed", e)
            }
            isCleaningDirty = false
            false
        }
    }

    private fun washWebView(webView: WebView) {
        webView.apply {
            clearFormData()
            clearHistory()
            loadDataWithBaseURL(
                "https://bbs.yamibo.com/?warmup=true",
                BLANK_HTML,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    fun deepWarmUp(context: Context) {
        checkMainThread("deepWarmUp")
        triggerAsyncReplenish(context.applicationContext)
    }

    fun acquire(context: Context): WebView {
        checkMainThread("acquire")
        scheduleCleanup()

        val holder = pool.pollFirst() ?: createWebViewHolder(context).also {
            triggerAsyncReplenish(context.applicationContext)
        }

        if (holder.isDirty) {
            washWebView(holder.webView)
            holder.isDirty = false
        }

        holder.webView.stopLoading()
        holder.contextWrapper.baseContext = context
        holder.useCount++

        holder.webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.webView.scrollTo(0, 0)
        holder.webView.onResume()

        activeHolders[holder.webView] = holder
        return holder.webView
    }

    fun release(webView: WebView) {
        checkMainThread("release")
        scheduleCleanup()

        val holder = activeHolders.remove(webView) ?: return
        val appContext = webView.context.applicationContext

        webView.apply {
            stopLoading()
            removeJavascriptInterface("ProberApi")
            removeJavascriptInterface("AndroidFullscreen")
            removeJavascriptInterface("NativeMangaApi")
            webViewClient = EMPTY_WEB_CLIENT
            webChromeClient = EMPTY_CHROME_CLIENT
            settings.apply {
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)
            onPause()
        }

        holder.contextWrapper.baseContext = appContext

        if (holder.useCount >= MAX_USES_PER_WEBVIEW) {
            discardHolder(holder)
            triggerAsyncReplenish(appContext)
        } else if (pool.size + activeHolders.size < MAX_POOL_SIZE) {
            webView.tag = "recycled_standby"
            holder.isDirty = true
            pool.addLast(holder)
            triggerAsyncWash()
        } else {
            discardHolder(holder)
        }
    }

    private fun discardHolder(holder: WebViewHolder) {
        try {
            val webView = holder.webView
            webView.stopLoading()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }

    fun discard(webView: WebView) {
        checkMainThread("discard")
        scheduleCleanup()

        activeHolders.remove(webView)?.let { discardHolder(it) }

        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            if (holder.webView === webView) {
                iterator.remove()
                discardHolder(holder)
                break
            }
        }

        triggerAsyncReplenish(webView.context.applicationContext)
    }

    private fun checkMainThread(methodName: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("Fatal Concurrency: WebViewPool.$methodName must be called on Main Thread!")
        }
    }

    private fun createWebViewHolder(context: Context): WebViewHolder {
        val contextWrapper = MutableContextWrapper(context.applicationContext)
        val webView = WebView(contextWrapper).apply {
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
            loadDataWithBaseURL(
                "https://bbs.yamibo.com/?warmup=true",
                BLANK_HTML,
                "text/html",
                "utf-8",
                null
            )
        }
        return WebViewHolder(webView, contextWrapper)
    }
}