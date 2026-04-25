package org.shirakawatyu.yamibo.novel.module

import android.content.Context
import android.webkit.WebResourceResponse
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline

/**
 * WebView 图片接管适配器。
 *
 * 这里不再维护独立的 in-flight map / Coil 下载 / DiskCache Snapshot 逻辑。
 * 所有漫画图片请求统一交给 MangaImagePipeline，保证：
 *
 * 1. WebView -> Coil -> Native 的接续语义不变。
 * 2. WebView takeover、Native 预加载、跨章节预取共享同一套 in-flight 去重。
 * 3. Cookie / Referer / cache key / force reload 语义统一。
 */
object CoilWebViewProxy {

    fun interceptImage(
        context: Context,
        url: String,
        headers: Map<String, String>?
    ): WebResourceResponse? {
        return MangaImagePipeline.interceptForWebView(
            context = context.applicationContext,
            url = url,
            headers = headers.orEmpty()
        )
    }
}
