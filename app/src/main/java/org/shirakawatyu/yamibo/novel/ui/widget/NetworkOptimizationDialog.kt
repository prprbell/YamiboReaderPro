package org.shirakawatyu.yamibo.novel.ui.widget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import java.net.InetAddress
import java.util.concurrent.TimeUnit
private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data object Success : TestState()
    data class Error(val message: String) : TestState()
}
@Composable
fun NetworkOptimizationDialog(onDismiss: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val enabled by GlobalData.isDnsOptimizationEnabled.collectAsState()
    val mode by GlobalData.dnsOptimizationMode.collectAsState()
    val customUrl by GlobalData.customDnsUrl.collectAsState()
    var tempEnabled by remember { mutableStateOf(enabled) }
    var tempMode by remember { mutableStateOf(mode) }
    var tempUrl by remember { mutableStateOf(customUrl) }
    var showUrlValidationError by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "网络优化设置",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 总开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "启用 DNS 优化",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = tempEnabled,
                            onCheckedChange = { tempEnabled = it }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                    // 选择区域
                    Text(
                        "解析方式",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // 自动模式卡片
                    OptionCard(
                        title = "自动",
                        description = "阿里 / 腾讯 DoH",
                        selected = tempMode == "auto",
                        onClick = {
                            tempMode = "auto"
                            testState = TestState.Idle
                        }
                    )
                    // 手动模式卡片
                    OptionCard(
                        title = "手动",
                        description = "指定 DoH 服务器",
                        selected = tempMode == "manual",
                        onClick = {
                            tempMode = "manual"
                            showUrlValidationError = false
                        }
                    )
                    // 手动输入区域
                    AnimatedVisibility(
                        visible = tempMode == "manual",
                        enter = expandVertically(animationSpec = tween(250)),
                        exit = shrinkVertically(animationSpec = tween(200))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val presetListRow1 = listOf(
                                "阿里" to "https://dns.alidns.com/dns-query",
                                "腾讯" to "https://doh.pub/dns-query",
                                "360" to "https://doh.360.cn/dns-query"
                            )
                            val presetListRow2 = listOf(
                                "Cloudflare" to "https://cloudflare-dns.com/dns-query",
                                "Google" to "https://dns.google/dns-query",
                                "Quad9" to "https://dns.quad9.net/dns-query"
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    presetListRow1.forEach { (name, url) ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (tempUrl == url) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    tempUrl = url
                                                    showUrlValidationError = false
                                                    testState = TestState.Idle
                                                }
                                        ) {
                                            Text(
                                                text = name,
                                                fontSize = 12.sp,
                                                color = if (tempUrl == url) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    presetListRow2.forEach { (name, url) ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (tempUrl == url) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    tempUrl = url
                                                    showUrlValidationError = false
                                                    testState = TestState.Idle
                                                }
                                        ) {
                                            Text(
                                                text = name,
                                                fontSize = 12.sp,
                                                color = if (tempUrl == url) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = tempUrl,
                                onValueChange = {
                                    tempUrl = it.trim()
                                    showUrlValidationError = false
                                    testState = TestState.Idle
                                },
                                label = { Text("DoH URL") },
                                isError = showUrlValidationError,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            if (showUrlValidationError) {
                                Text(
                                    "请输入有效的 HTTPS 地址",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            // 测试连通性按钮
                            val canTest = tempUrl.isNotBlank() && tempUrl.startsWith("https://")
                            OutlinedButton(
                                onClick = {
                                    if (testState != TestState.Testing) {
                                        coroutineScope.launch {
                                            testState = TestState.Testing
                                            try {
                                                val testClient = OkHttpClient.Builder()
                                                    .connectTimeout(3, TimeUnit.SECONDS)
                                                    .build()
                                                val dns = DnsOverHttps.Builder()
                                                    .client(testClient)
                                                    .url(tempUrl.toHttpUrl())
                                                    .build()
                                                val addresses = withContext(Dispatchers.IO) {
                                                    dns.lookup("bbs.yamibo.com")
                                                }
                                                testState = if (addresses.isNotEmpty()) {
                                                    TestState.Success
                                                } else {
                                                    TestState.Error("解析结果为空")
                                                }
                                            } catch (e: Exception) {
                                                testState = TestState.Error(e.message ?: "连接失败")
                                            }
                                        }
                                    }
                                },
                                enabled = canTest && testState != TestState.Testing,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (testState == TestState.Testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("测试中...")
                                } else {
                                    Text("测试连接")
                                }
                            }
                            // 测试结果显示
                            when (val state = testState) {
                                is TestState.Success -> {
                                    Text(
                                        "✓ 连接正常",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                    )
                                }
                                is TestState.Error -> {
                                    Text(
                                        "✗ ${state.message}",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tempEnabled && tempMode == "manual" &&
                        (tempUrl.isBlank() || !tempUrl.startsWith("https://"))
                    ) {
                        showUrlValidationError = true
                        return@TextButton
                    }
                    coroutineScope.launch {
                        SettingsUtil.saveDnsOptimizationEnabled(tempEnabled)
                        SettingsUtil.saveDnsOptimizationMode(tempMode)
                        SettingsUtil.saveCustomDnsUrl(tempUrl)
                    }
                    GlobalData.isCustomDnsEnabled.value = tempEnabled
                    GlobalData.isDnsOptimizationEnabled.value = tempEnabled
                    GlobalData.dnsOptimizationMode.value = tempMode
                    GlobalData.customDnsUrl.value = tempUrl
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确定", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 修改点：缩短以下三个动画的时间参数以消除切换卡顿
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(100),
        label = "borderColor"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f),
        animationSpec = tween(100),
        label = "backgroundColor"
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(100),
        label = "titleColor"
    )
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = titleColor
                )
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}