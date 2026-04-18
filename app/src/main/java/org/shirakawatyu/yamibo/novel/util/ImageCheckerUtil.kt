package org.shirakawatyu.yamibo.novel.util

import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/**
 * 垃圾图/错误图拦截工具类
 */
object ImageCheckerUtil {
    private const val TAG = "ImageCheckerUtil"

    // 漫画图最小体积阈值（15KB）
    private const val MIN_IMAGE_SIZE_BYTES = 15 * 1024L
    // 合法的图片 Content-Type 白名单
    private val VALID_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")

    /**
     * 终极防线：流式拦截图片响应
     * 包含 Header 校验和底层流式 EOF (文件尾) 校验，防止半截黑屏图污染缓存。
     */
    @Throws(IOException::class)
    fun interceptAndCheckImageStream(response: Response, url: String): Response {
        val isForumAttachment = url.contains("attachment/forum", ignoreCase = true)
        val hasImageExtension = url.contains(Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE))

        if (!isForumAttachment && !hasImageExtension) {
            return response
        }

        val contentType = response.header("Content-Type") ?: ""
        val contentLength = response.body?.contentLength() ?: -1L

        // 1. 检查 Content-Type
        if (contentType.isNotEmpty()) {
            val isImage = VALID_IMAGE_TYPES.any { contentType.contains(it, ignoreCase = true) }
            if (!isImage && !contentType.contains("application/octet-stream", ignoreCase = true)) {
                Log.e(TAG, "拦截非法图片格式 (Content-Type: $contentType) -> URL: $url")
                throw IOException("Invalid Content-Type: $contentType. Suspected HTML error page.")
            }
        }

        // 2. 检查声明体积
        if (contentLength in 1 until MIN_IMAGE_SIZE_BYTES) {
            Log.e(TAG, "拦截极小体积图片 (Size: $contentLength bytes) -> URL: $url")
            throw IOException("Image size too small ($contentLength bytes). Suspected garbage image.")
        }

        // 3. 构建数据流拦截器（防半截黑屏图，零内存额外拷贝）
        val originalBody = response.body ?: return response
        val isJpeg = url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true) || contentType.contains("jpeg", ignoreCase = true)
        val isPng = url.contains(".png", ignoreCase = true) || contentType.contains("png", ignoreCase = true)

        val eofCheckingSource = object : ForwardingSource(originalBody.source()) {
            var prevByte = -1
            var lastByte = -1
            var totalBytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead > 0) {
                    totalBytesRead += bytesRead
                    // 动态获取当前读入块的最后两个字节
                    if (bytesRead >= 2) {
                        prevByte = sink[sink.size - 2].toInt() and 0xFF
                        lastByte = sink[sink.size - 1].toInt() and 0xFF
                    } else {
                        prevByte = lastByte
                        lastByte = sink[sink.size - 1].toInt() and 0xFF
                    }
                } else if (bytesRead == -1L) {
                    // 【绝杀核心】流读取完毕，检查底层数据是否完整

                    if (totalBytesRead < MIN_IMAGE_SIZE_BYTES) {
                        Log.e(TAG, "拦截极小体积图片 (实际下载 Size: $totalBytesRead) -> URL: $url")
                        throw IOException("Actual downloaded image size too small ($totalBytesRead bytes).")
                    }

                    // JPEG 必须以 0xFF 0xD9 结尾
                    if (isJpeg) {
                        if (prevByte != 0xFF || lastByte != 0xD9) {
                            throw IOException("Incomplete JPEG image detected (Missing EOF marker).")
                        }
                    }
                    // PNG 必须以 IEND 块结尾，其最后两个字节必然是 0x60 0x82
                    else if (isPng) {
                        if (prevByte != 0x60 || lastByte != 0x82) {
                            throw IOException("Incomplete PNG image detected (Missing IEND chunk).")
                        }
                    }
                }
                return bytesRead
            }
        }

        // 返回包含 EOF 监听流的全新 Response
        val newBody = eofCheckingSource.buffer().asResponseBody(originalBody.contentType(), contentLength)
        return response.newBuilder().body(newBody).build()
    }

    fun isGarbageByBounds(width: Int, height: Int): Boolean {
        return width > 0 && height > 0 && width < 300 && height < 300
    }

    fun checkImageBoundsValid(imageBytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        return !isGarbageByBounds(options.outWidth, options.outHeight)
    }
}