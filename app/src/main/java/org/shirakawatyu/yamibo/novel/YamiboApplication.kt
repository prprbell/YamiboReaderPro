package org.shirakawatyu.yamibo.novel

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.shirakawatyu.yamibo.novel.util.WebViewPool

class YamiboApplication : Application(), ImageLoaderFactory {

    companion object {
        var systemUserAgent: String = ""
    }

    override fun onCreate() {
        super.onCreate()

        WebViewPool.init(this)

        Looper.myQueue().addIdleHandler {
            try {
                systemUserAgent = WebSettings.getDefaultUserAgent(this@YamiboApplication)
            } catch (e: Exception) {
                systemUserAgent = System.getProperty("http.agent") ?: ""
            }
            false
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var hasWarmedUp = false

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!hasWarmedUp) {
                    Looper.myQueue().addIdleHandler {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            WebViewPool.deepWarmUp(activity)
                            hasWarmedUp = true
                        }
                        false
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}