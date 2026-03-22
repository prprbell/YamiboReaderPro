package org.shirakawatyu.yamibo.novel

import android.app.Application
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.shirakawatyu.yamibo.novel.util.WebViewPool

class YamiboApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()

        Handler(Looper.getMainLooper()).postDelayed({
            WebViewPool.init(this)
        }, 1500)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.40)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(168L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}