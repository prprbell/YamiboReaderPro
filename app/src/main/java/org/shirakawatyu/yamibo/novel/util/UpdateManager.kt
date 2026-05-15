package org.shirakawatyu.yamibo.novel.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.shirakawatyu.yamibo.novel.BuildConfig
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val size: Long,
    val body: String
)

object UpdateManager {
    private const val GITEE_TOKEN = "dc288904e9ab77c002d79d9b9b3aa819"
    private const val GITEE_LATEST = "https://gitee.com/api/v5/repos/windcloudjet/YamiboReaderPro/releases/latest"
    private const val GITHUB_LATEST = "https://api.github.com/repos/prprbell/YamiboReaderPro/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(YamiboRetrofit.okHttpClient.dns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class ReleaseAsset(
        val name: String = "",
        val size: Long = 0,
        val browser_download_url: String = ""
    )

    data class ApiRelease(
        val tag_name: String = "",
        val body: String = "",
        val assets: List<ReleaseAsset>? = null,
        val attach_files: List<ReleaseAsset>? = null
    ) {
        val apkAsset: ReleaseAsset?
            get() = attach_files?.firstOrNull { it.name.endsWith(".apk") }
                ?: assets?.firstOrNull { it.name.endsWith(".apk") }
    }

    /** 比较两个版本名字符串（如 "1.10.2" vs "v1.9.22"），返回 >0 / 0 / <0 */
    fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.removePrefix("v").removePrefix("v.").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.removePrefix("v").removePrefix("v.").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /** 从版本名字符串中估算 versionCode（取末段数字） */
    private fun estimateVersionCode(versionName: String): Int {
        val parts = versionName.removePrefix("v").removePrefix("v.").split(".")
        return parts.lastOrNull()?.toIntOrNull() ?: 0
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = fetchFromGitee() ?: fetchFromGithub() ?: return@withContext null
        val apkAsset = release.apkAsset ?: return@withContext null
        val latestVersion = release.tag_name.removePrefix("v").removePrefix("v.")
        val latestCode = estimateVersionCode(latestVersion)

        if (compareVersion(latestVersion, BuildConfig.VERSION_NAME) > 0 || latestCode > BuildConfig.VERSION_CODE) {
            UpdateInfo(
                versionName = release.tag_name,
                versionCode = latestCode,
                downloadUrl = apkAsset.browser_download_url,
                size = apkAsset.size,
                body = release.body
            )
        } else {
            null
        }
    }

    private fun fetchFromGitee(): ApiRelease? {
        return try {
            val request = Request.Builder()
                .url("$GITEE_LATEST?access_token=$GITEE_TOKEN")
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSON.parseObject(it, ApiRelease::class.java) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromGithub(): ApiRelease? {
        return try {
            val request = Request.Builder()
                .url(GITHUB_LATEST)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSON.parseObject(it, ApiRelease::class.java) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 使用系统 DownloadManager 下载 APK，完成后返回下载 ID */
    fun downloadViaManager(context: Context, info: UpdateInfo): Long {
        val fileName = "yamibo_${info.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl + "?access_token=$GITEE_TOKEN"))
            .setTitle("百合会阅读器更新")
            .setDescription("正在下载 v${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /** 监听下载完成并尝试安装 */
    fun registerDownloadReceiver(context: Context, downloadId: Long, onComplete: (File) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uriStr = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        cursor.close()
                        uriStr?.let {
                            // 从 content URI 复制到缓存目录以便 FileProvider 访问
                            val apkFile = copyToCache(context, Uri.parse(it))
                            if (apkFile != null) {
                                onComplete(apkFile)
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        return receiver
    }

    private fun copyToCache(context: Context, uri: Uri): File? {
        return try {
            val cacheRoot = context.externalCacheDir ?: context.cacheDir
            val dest = File(cacheRoot, "update")
            dest.mkdirs()
            val apkFile = File(dest, "update.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            apkFile
        } catch (_: Exception) {
            null
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(intent)
    }
}
