package org.shirakawatyu.yamibo.novel.util

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.shirakawatyu.yamibo.novel.YamiboApplication
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val body: String
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val UPDATE_JSON_URL = "https://yamibo-reader-pro-release.oss-cn-chengdu.aliyuncs.com/update.json"

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(YamiboRetrofit.okHttpClient.dns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

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

    private fun estimateVersionCode(versionName: String): Int {
        val parts = versionName.removePrefix("v").removePrefix("v.").split(".")
        return parts.lastOrNull()?.toIntOrNull() ?: 0
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = YamiboApplication.application
        val packageInfo = try {
            app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(TAG, "checkForUpdate: cannot get packageInfo")
            return@withContext null
        }
        val currentVersionCode = packageInfo.versionCode
        val currentVersionName = packageInfo.versionName ?: "0"
        Log.d(TAG, "checkForUpdate: current=$currentVersionName code=$currentVersionCode")

        val request = Request.Builder()
            .url(UPDATE_JSON_URL)
            .header("Cache-Control", "no-cache")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdate: request failed: ${e.message}")
            return@withContext null
        }

        if (!response.isSuccessful) {
            Log.w(TAG, "checkForUpdate: HTTP ${response.code}")
            return@withContext null
        }
        SettingsUtil.saveLastUpdateCheckTime(System.currentTimeMillis())
        val body = response.body?.string() ?: run {
            Log.w(TAG, "checkForUpdate: empty body")
            return@withContext null
        }
        Log.d(TAG, "checkForUpdate: body=${body.take(300)}")

        try {
            val json = JSON.parseObject(body)
            val latestVersion = json.getString("tag_name") ?: run {
                Log.w(TAG, "checkForUpdate: tag_name missing")
                return@withContext null
            }
            val pureVersion = latestVersion.removePrefix("v").removePrefix("v.")
            val latestCode = estimateVersionCode(pureVersion)
            val downloadUrl = json.getString("apkDownloadUrl") ?: ""

            if (compareVersion(pureVersion, currentVersionName) > 0 || latestCode > currentVersionCode) {
                Log.i(TAG, "checkForUpdate: NEW version: $latestVersion (code=$latestCode) url=$downloadUrl")
                return@withContext UpdateInfo(
                    versionName = latestVersion,
                    versionCode = latestCode,
                    downloadUrl = downloadUrl,
                    body = json.getString("body") ?: ""
                )
            } else {
                Log.d(TAG, "checkForUpdate: up-to-date ($pureVersion <= $currentVersionName)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdate: parse failed: ${e.message}")
        }

        return@withContext null
    }

    fun downloadViaManager(context: Context, info: UpdateInfo): Long {
        val fileName = "yamibo_${info.versionName}.apk"
        val request = DownloadManager.Request(info.downloadUrl.toUri())
            .setTitle("YamiboReaderPro")
            .setDescription("正在下载 v${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /** 监听下载完成并尝试安装，回调后自动注销 */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerDownloadReceiver(context: Context, downloadId: Long, onComplete: (File) -> Unit): BroadcastReceiver {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                runCatching { appContext.unregisterReceiver(this) }

                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query) ?: return
                cursor.use { c ->
                    if (!c.moveToFirst()) return

                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        Log.w(TAG, "download failed or cancelled: id=$downloadId status=$status")
                        return
                    }

                    val uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (uriStr.isNullOrBlank()) {
                        Log.w(TAG, "download completed but local uri is empty: id=$downloadId")
                        return
                    }

                    val apkFile = copyToCache(appContext, uriStr.toUri())
                    if (apkFile != null) {
                        onComplete(apkFile)
                    } else {
                        Log.w(TAG, "copy downloaded apk to cache failed: id=$downloadId")
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            appContext.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
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
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}