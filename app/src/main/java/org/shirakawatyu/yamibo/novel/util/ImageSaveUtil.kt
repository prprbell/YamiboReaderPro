package org.shirakawatyu.yamibo.novel.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import java.io.File
import java.io.FileOutputStream

object ImageSaveUtil {

    suspend fun saveImage(context: Context, url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val client = YamiboRetrofit.okHttpClient

                val request = Request.Builder()
                    .url(url)
                    .header("Referer", "https://bbs.yamibo.com/")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful || response.body == null) {
                    withContext(Dispatchers.Main) {
                        YamiboToast.show(context = context, message = "下载图片失败")
                    }
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val bytes = response.body!!.bytes()
                val fileName = url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "image_${System.currentTimeMillis()}.jpg" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Yamibo")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                    )
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "Yamibo"
                    )
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { it.write(bytes) }
                }

                withContext(Dispatchers.Main) {
                    YamiboToast.show(context = context, message = "保存成功")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    YamiboToast.show(context = context, message = "保存失败: ${e.message}")
                }
                Result.failure(e)
            }
        }
}
