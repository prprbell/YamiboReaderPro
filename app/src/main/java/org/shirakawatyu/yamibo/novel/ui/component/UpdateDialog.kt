package org.shirakawatyu.yamibo.novel.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.util.UpdateInfo
import org.shirakawatyu.yamibo.novel.util.UpdateManager
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import androidx.core.net.toUri

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onSkipVersion: (String) -> Unit
) {
    val context = LocalContext.current
    var startingDownload by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!startingDownload) onDismiss()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("发现新版本", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    enabled = !startingDownload,
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(maxHeight = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "版本 ${info.versionName}",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                if (info.body.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = info.body,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !startingDownload,
                onClick = {
                    if (startingDownload) return@Button

                    if (info.downloadUrl.isBlank()) {
                        YamiboToast.show(context = context, message = "更新包下载地址为空", durationMillis = YamiboToast.LENGTH_LONG)
                        return@Button
                    }

                    if (!canInstallApk(context)) {
                        openInstallPermissionSettings(context)
                        return@Button
                    }

                    startingDownload = true
                    try {
                        val appContext = context.applicationContext
                        val id = UpdateManager.downloadViaManager(appContext, info)
                        UpdateManager.registerDownloadReceiver(appContext, id) { file ->
                            UpdateManager.installApk(appContext, file)
                        }
                        YamiboToast.show(context = context, message = "正在下载更新...")
                        onDismiss()
                    } catch (e: Exception) {
                        startingDownload = false
                        YamiboToast.show(
                            context = context,
                            message = "启动下载失败：${e.message ?: "未知错误"}",
                            durationMillis = YamiboToast.LENGTH_LONG
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (startingDownload) "准备中..." else "立即更新")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !startingDownload,
                onClick = {
                    onSkipVersion(info.versionName)
                    onDismiss()
                }
            ) {
                Text("忽略此版本")
            }
        }
    )
}

private fun canInstallApk(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return context.packageManager.canRequestPackageInstalls()
    }
    return true
}

private fun openInstallPermissionSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
