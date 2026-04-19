package org.shirakawatyu.yamibo.novel

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.brotli.BrotliInterceptor
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.util.NetworkPreWarmer
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.File

class YamiboApplication : Application(), ImageLoaderFactory {

    companion object {
        lateinit var application: Application
            private set
        lateinit var globalCacheDir: File
            private set
        var systemUserAgent: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        globalCacheDir = applicationContext.cacheDir

        GlobalData.dataStore = applicationContext.dataStore

        SettingsUtil.getAutoClearCacheMode { isEnabled ->
            GlobalData.isAutoClearCacheEnabled.value = isEnabled
            if (isEnabled) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        this@YamiboApplication.imageLoader.diskCache?.clear()
                        this@YamiboApplication.imageLoader.memoryCache?.clear()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                globalCacheDir.listFiles { _, name -> name.startsWith("coil_trash_") }?.forEach {
                    it.deleteRecursively()
                }
            } catch (_: Exception) {
                // 忽略极端情况下的文件占用异常
            }
        }

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
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
        })
    }

    override fun newImageLoader(): ImageLoader {
        val coilDefaultClient by lazy {
            YamiboRetrofit.okHttpClient.newBuilder()
                .addInterceptor(BrotliInterceptor)
                .build()
        }

        val coilThreadClient by lazy {
            YamiboRetrofit.threadOkHttpClient.newBuilder()
                .addInterceptor(BrotliInterceptor)
                .build()
        }

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(File(globalCacheDir, "coil_image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .callFactory { request ->
                val url = request.url.toString()
                if (url.contains("attachment/forum", ignoreCase = true)) {
                    coilThreadClient.newCall(request)
                } else {
                    coilDefaultClient.newCall(request)
                }
            }
            .crossfade(false)
            .build()
    }
}