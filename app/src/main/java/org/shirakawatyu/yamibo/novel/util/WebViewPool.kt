package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.IdentityHashMap


object WebViewPool {
    private class WebViewHolder(
        val webView: WebView,
        val contextWrapper: MutableContextWrapper
    ) {
        var useCount = 0
    }

    private val pool = ArrayDeque<WebViewHolder>()
    private val activeHolders = IdentityHashMap<WebView, WebViewHolder>()
    private val warmingUpSet = IdentityHashMap<WebView, WebViewHolder>()

    private const val MAX_POOL_SIZE = 3
    private const val MAX_USES_PER_WEBVIEW = 8

    private val EMPTY_WEB_CLIENT = WebViewClient()
    private val EMPTY_CHROME_CLIENT = WebChromeClient()

    // 防止重复注册 IdleHandler
    private var isReplenishing = false

    fun init(context: Context) {
        checkMainThread("init")
        triggerAsyncReplenish(context.applicationContext)
    }

    private fun triggerAsyncReplenish(appContext: Context) {
        if (isReplenishing) return
        isReplenishing = true

        Looper.myQueue().addIdleHandler {
            if (pool.size + warmingUpSet.size + activeHolders.size < MAX_POOL_SIZE) {
                val holder = createWebViewHolder(appContext)
                holder.webView.tag = "recycled_standby"
                pool.addLast(holder)

                return@addIdleHandler true
            }
            isReplenishing = false
            false
        }
    }

    fun deepWarmUp(activity: Activity) {
        checkMainThread("deepWarmUp")
        val holder = pool.removeFirstOrNull() ?: return
        val webView = holder.webView
        warmingUpSet[webView] = holder

        val decorView = activity.window.decorView as? ViewGroup
        val appContext = activity.applicationContext

        if (decorView == null) {
            rollbackWarmUp(holder, appContext)
            return
        }

        val lifecycleCallback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(act: Activity) {
                if (act === activity && warmingUpSet.containsKey(webView)) {
                    cleanupWarmUp(holder, decorView, appContext, this)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }
        activity.application.registerActivityLifecycleCallbacks(lifecycleCallback)

        val layoutParams = FrameLayout.LayoutParams(1, 1)

        try {
            holder.contextWrapper.baseContext = activity
            webView.alpha = 0.01f
            decorView.addView(webView, layoutParams)
            webView.loadDataWithBaseURL(
                "https://bbs.yamibo.com/",
                "<html><body></body></html>",
                "text/html",
                "utf-8",
                null
            )

            Looper.myQueue().addIdleHandler {
                if (warmingUpSet.containsKey(webView) && !activity.isDestroyed) {
                    cleanupWarmUp(holder, decorView, appContext, lifecycleCallback)
                }
                false
            }
        } catch (e: Exception) {
            cleanupWarmUp(holder, decorView, appContext, lifecycleCallback)
        }
    }

    private fun cleanupWarmUp(
        holder: WebViewHolder,
        decorView: ViewGroup,
        appContext: Context,
        callback: Application.ActivityLifecycleCallbacks
    ) {
        try {
            decorView.removeView(holder.webView)
        } catch (ignored: Exception) {
        }
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(callback)
        rollbackWarmUp(holder, appContext)
    }

    private fun rollbackWarmUp(holder: WebViewHolder, appContext: Context) {
        holder.contextWrapper.baseContext = appContext
        warmingUpSet.remove(holder.webView)
        if (pool.size + activeHolders.size < MAX_POOL_SIZE) {
            pool.addLast(holder)
        } else {
            discardHolder(holder)
        }
        triggerAsyncReplenish(appContext)
    }

    fun acquire(context: Context): WebView {
        checkMainThread("acquire")
        val holder = pool.removeFirstOrNull() ?: createWebViewHolder(context).also {
            triggerAsyncReplenish(context.applicationContext)
        }

        holder.webView.stopLoading()

        holder.contextWrapper.baseContext = context
        holder.useCount++
        holder.webView.alpha = 1.0f
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
        if (warmingUpSet.containsKey(webView)) return

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
            clearFormData()
            clearHistory()
            removeAllViews()
            (parent as? ViewGroup)?.removeView(this)

            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            onPause()
        }

        holder.contextWrapper.baseContext = appContext

        if (holder.useCount >= MAX_USES_PER_WEBVIEW) {
            discardHolder(holder)
            triggerAsyncReplenish(appContext)
        } else if (pool.size + activeHolders.size + warmingUpSet.size < MAX_POOL_SIZE) {
            webView.tag = "recycled_standby"
            pool.addLast(holder)
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
        } catch (ignored: Exception) {
        }
    }

    fun discard(webView: WebView) {
        checkMainThread("discard")
        activeHolders.remove(webView)?.let { discardHolder(it) }
        warmingUpSet.remove(webView)?.let { discardHolder(it) }
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
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        }
        return WebViewHolder(webView, contextWrapper)
    }
}