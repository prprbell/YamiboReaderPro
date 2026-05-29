package org.shirakawatyu.yamibo.novel.module

import android.content.Context
import android.webkit.WebResourceResponse
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline

/**
 * WebView 图片接管适配器
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
