package org.shirakawatyu.yamibo.novel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import org.shirakawatyu.yamibo.novel.util.WebViewPool

class YamiboApplication : Application(), ImageLoaderFactory {

    companion object {
        // 用于全局保存系统真实的WebView UA
        var systemUserAgent: String = ""
    }

    override fun onCreate() {
        super.onCreate()

        // 启动时获取当前设备真实的User-Agent
        try {
            systemUserAgent = WebSettings.getDefaultUserAgent(this)
        } catch (e: Exception) {
            systemUserAgent = System.getProperty("http.agent") ?: ""
        }

        Handler(Looper.getMainLooper()).postDelayed({
            WebViewPool.init(this)
        }, 1500)
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