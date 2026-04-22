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
 * 垃圾图/错误图拦截工具类 (极简稳定版)
 */
object ImageCheckerUtil {
    private const val TAG = "ImageCheckerUtil"

    private const val MIN_IMAGE_SIZE_BYTES = 3 * 1024L
    // 合法的图片 Content-Type 白名单
    private val VALID_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")

    @Throws(IOException::class)
    fun interceptAndCheckImageStream(response: Response, url: String): Response {
        val isForumAttachment = url.contains("attachment/forum", ignoreCase = true)
        val hasImageExtension = url.contains(Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE))

        if (!isForumAttachment && !hasImageExtension) {
            return response
        }

        val contentType = response.header("Content-Type") ?: ""
        val contentLength = response.body?.contentLength() ?: -1L

        if (contentType.isNotEmpty()) {
            val isImage = VALID_IMAGE_TYPES.any { contentType.contains(it, ignoreCase = true) }
            if (!isImage && !contentType.contains("application/octet-stream", ignoreCase = true)) {
                Log.e(TAG, "拦截非法图片格式 (Content-Type: $contentType) -> URL: $url")
                throw IOException("Invalid Content-Type: $contentType. Suspected HTML error page.")
            }
        }

        if (contentLength in 1 until MIN_IMAGE_SIZE_BYTES) {
            Log.e(TAG, "拦截极小体积图片 (Size: $contentLength bytes) -> URL: $url")
            throw IOException("Image size too small ($contentLength bytes). Suspected garbage image.")
        }

        val originalBody = response.body ?: return response

        val sizeCheckingSource = object : ForwardingSource(originalBody.source()) {
            var totalBytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead > 0) {
                    totalBytesRead += bytesRead
                } else if (bytesRead == -1L) {
                    if (totalBytesRead < MIN_IMAGE_SIZE_BYTES) {
                        Log.e(TAG, "拦截极小体积图片 (实际下载 Size: $totalBytesRead) -> URL: $url")
                        throw IOException("Actual downloaded image size too small ($totalBytesRead bytes).")
                    }
                }
                return bytesRead
            }
        }

        val newBody = sizeCheckingSource.buffer().asResponseBody(originalBody.contentType(), contentLength)
        return response.newBuilder().body(newBody).build()
    }

    fun isGarbageByBounds(width: Int, height: Int): Boolean {
        return width > 0 && height > 0 && width < 150 && height < 150
    }

    fun checkImageBoundsValid(imageBytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        return !isGarbageByBounds(options.outWidth, options.outHeight)
    }
}