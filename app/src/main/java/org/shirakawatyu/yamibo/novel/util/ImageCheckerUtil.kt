package org.shirakawatyu.yamibo.novel.util

import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Response
import java.io.IOException

/**
 * 错误图拦截工具类
 */
object ImageCheckerUtil {
    private const val TAG = "ImageCheckerUtil"

    // 漫画图最小体积阈值（15KB），低于此值的被认为是报错图、半截图或透明占位图
    private const val MIN_IMAGE_SIZE_BYTES = 15 * 1024L

    // 合法的图片 Content-Type 白名单
    private val VALID_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")

    /**
     * 第一道防线：通过 HTTP 响应头快速拦截（体积与格式比对）
     * 适用于 OkHttp Interceptor。如果在该阶段抛出 IOException，网络库会认为请求失败，绝对不会落盘缓存。
     *
     * @param response OkHttp 的响应对象
     * @param url 请求的 URL，用于区分是否是需要拦截的漫画图片
     * @throws IOException 如果判定为垃圾图，直接抛出异常切断下载
     */
    @Throws(IOException::class)
    fun checkOkHttpResponseHeaders(response: Response, url: String) {
        // 过滤：只对论坛的附件图片或明确有图片后缀的 URL 进行严格校验，避免误伤普通网页加载
        val isForumAttachment = url.contains("attachment/forum", ignoreCase = true)
        val hasImageExtension = url.contains(Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE))
        
        if (!isForumAttachment && !hasImageExtension) {
            return
        }

        val contentType = response.header("Content-Type") ?: ""
        val contentLength = response.body?.contentLength() ?: -1L

        // 1. 检查 Content-Type (防御返回 502 或 404 HTML 文本，但状态码却是 200 的情况)
        // 注意：有的服务器可能不返回 Content-Type，所以只有在返回了且不合法时才拦截
        if (contentType.isNotEmpty()) {
            val isImage = VALID_IMAGE_TYPES.any { contentType.contains(it, ignoreCase = true) }
            if (!isImage && !contentType.contains("application/octet-stream", ignoreCase = true)) {
                Log.e(TAG, "拦截非法图片格式 (Content-Type: $contentType) -> URL: $url")
                throw IOException("Invalid Content-Type: $contentType. Suspected HTML error page.")
            }
        }

        // 2. 检查体积下限 (防御体积只有几KB的极限模糊图、1x1占位图)
        // 注意：如果使用了 Transfer-Encoding: chunked 分块传输，Content-Length 会是 -1，放行靠后续处理
        if (contentLength in 1 until MIN_IMAGE_SIZE_BYTES) {
            Log.e(TAG, "拦截极小体积图片 (Size: $contentLength bytes) -> URL: $url")
            throw IOException("Image size too small ($contentLength bytes). Suspected garbage or placeholder image.")
        }
    }

    /**
     * 第二道防线：通过图片物理尺寸拦截（防御经过体积伪装、但其实只有几个像素的报错图）
     *
     * @param width 图片宽
     * @param height 图片高
     * @return true 如果是垃圾图
     */
    fun isGarbageByBounds(width: Int, height: Int): Boolean {
        // 正常的漫画页分辨率极少会低于 300x300
        // 如果长宽都很小（比如 1x1, 150x50 等），大概率是论坛的“图片已失效”占位图标
        return width > 0 && height > 0 && width < 300 && height < 300
    }

    /**
     * 辅助方法：只解码图片边界（不加载到内存），快速判断尺寸是否合法
     * 适用于拿到 InputStream 或 ByteArray 后的二次校验
     */
    fun checkImageBoundsValid(imageBytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true // 只读属性，不耗费过多内存
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        return !isGarbageByBounds(options.outWidth, options.outHeight)
    }
}