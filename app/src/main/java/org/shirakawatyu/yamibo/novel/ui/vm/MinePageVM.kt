package org.shirakawatyu.yamibo.novel.ui.vm

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import org.shirakawatyu.yamibo.novel.util.WebViewPool

@SuppressLint("StaticFieldLeak")
class MinePageVM : ViewModel() {
    var cachedWebView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var releaseRunnable: Runnable? = null

    fun getOrAcquireWebView(context: Context): WebView {
        cancelRelease()
        if (cachedWebView == null) {
            cachedWebView = WebViewPool.acquire(context)
        } else {
            (cachedWebView?.context as? MutableContextWrapper)?.baseContext = context
        }
        return cachedWebView!!
    }

    // 延迟60秒销毁
    fun scheduleRelease(delayMs: Long = 300000L) {
        cancelRelease()
        releaseRunnable = Runnable {
            cachedWebView?.let { webView ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                WebViewPool.release(webView)
            }
            cachedWebView = null
            releaseRunnable = null
        }
        handler.postDelayed(releaseRunnable!!, delayMs)
    }

    fun cancelRelease() {
        releaseRunnable?.let { handler.removeCallbacks(it) }
        releaseRunnable = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelRelease()
        cachedWebView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            WebViewPool.release(webView)
        }
        cachedWebView = null
    }
}