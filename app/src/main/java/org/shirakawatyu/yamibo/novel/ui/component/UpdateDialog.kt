package org.shirakawatyu.yamibo.novel.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.util.UpdateInfo
import org.shirakawatyu.yamibo.novel.util.UpdateManager

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onSkipVersion: (String) -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("发现新版本", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
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
                onClick = {
                    if (!canInstallApk(context)) {
                        Toast.makeText(context, "请允许安装未知来源应用", Toast.LENGTH_LONG).show()
                        openInstallPermissionSettings(context)
                        return@Button
                    }
                    val id = UpdateManager.downloadViaManager(context, info)
                    UpdateManager.registerDownloadReceiver(context, id) { file ->
                        UpdateManager.installApk(context, file)
                    }
                    Toast.makeText(context, "正在下载更新...", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            TextButton(
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
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
