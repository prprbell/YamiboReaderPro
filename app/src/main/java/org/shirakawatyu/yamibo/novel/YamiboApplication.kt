package org.shirakawatyu.yamibo.novel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.brotli.BrotliInterceptor
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.util.NetworkPreWarmer
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.File
import java.util.concurrent.TimeUnit

class YamiboApplication : Application(), ImageLoaderFactory {

    companion object {
        lateinit var globalCacheDir: File
            private set
        var systemUserAgent: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        globalCacheDir = applicationContext.cacheDir
        WebViewPool.init(this)

        Looper.myQueue().addIdleHandler {
            try {
                systemUserAgent = WebSettings.getDefaultUserAgent(this@YamiboApplication)
            } catch (_: Exception) {
                systemUserAgent = System.getProperty("http.agent") ?: ""
            }

            NetworkPreWarmer.warmUp()

            false
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var hasWarmedUp = false

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!hasWarmedUp) {
                    Looper.myQueue().addIdleHandler {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            WebViewPool.deepWarmUp(this@YamiboApplication)
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
            .okHttpClient {
                YamiboRetrofit.okHttpClient.newBuilder()
                    .cache(null)
                    .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                    .addInterceptor(BrotliInterceptor)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}