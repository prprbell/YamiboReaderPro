package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import java.util.concurrent.TimeUnit

private const val DNS_UI_ANIM_DURATION = 300

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

    val sizeSpec = tween<IntSize>(
        durationMillis = DNS_UI_ANIM_DURATION,
        easing = FastOutSlowInEasing
    )

    LaunchedEffect(tempEnabled) {
        if (!tempEnabled) {
            delay(DNS_UI_ANIM_DURATION.toLong())
            showUrlValidationError = false
            testState = TestState.Idle
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "网络优化设置",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "启用 DNS 优化",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "加速图片加载与 API 访问",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Switch(
                            checked = tempEnabled,
                            onCheckedChange = { checked ->
                                tempEnabled = checked
                            }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = tempEnabled,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = DNS_UI_ANIM_DURATION,
                            easing = FastOutSlowInEasing
                        )
                    ) + expandVertically(
                        animationSpec = sizeSpec,
                        expandFrom = Alignment.Top
                    ),
                    exit = fadeOut(
                        animationSpec = tween(
                            durationMillis = DNS_UI_ANIM_DURATION,
                            easing = FastOutSlowInEasing
                        )
                    ) + shrinkVertically(
                        animationSpec = sizeSpec,
                        shrinkTowards = Alignment.Top
                    )
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = "解析方式",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(4.dp)
                        ) {
                            SegmentButton(
                                text = "自动推荐",
                                isSelected = tempMode == "auto",
                                onClick = {
                                    tempMode = "auto"
                                    testState = TestState.Idle
                                    showUrlValidationError = false
                                },
                                modifier = Modifier.weight(1f)
                            )

                            SegmentButton(
                                text = "手动配置",
                                isSelected = tempMode == "manual",
                                onClick = {
                                    tempMode = "manual"
                                    showUrlValidationError = false
                                    testState = TestState.Idle
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ModeContent(
                            mode = tempMode,
                            tempUrl = tempUrl,
                            onTempUrlChange = {
                                tempUrl = it
                                showUrlValidationError = false
                                testState = TestState.Idle
                            },
                            showUrlValidationError = showUrlValidationError,
                            testState = testState,
                            onTestStateChange = { testState = it },
                            coroutineScopeLaunchTest = {
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
                            },
                            sizeSpec = sizeSpec
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (
                        tempEnabled &&
                        tempMode == "manual" &&
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
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun ModeContent(
    mode: String,
    tempUrl: String,
    onTempUrlChange: (String) -> Unit,
    showUrlValidationError: Boolean,
    testState: TestState,
    onTestStateChange: (TestState) -> Unit,
    coroutineScopeLaunchTest: () -> Unit,
    sizeSpec: FiniteAnimationSpec<IntSize>
) {
    AnimatedContent(
        targetState = mode,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            val switchingToAuto = targetState == "auto"
            val switchingToManual = targetState == "manual"

            val enterTransition =
                fadeIn(
                    animationSpec = tween(
                        durationMillis = DNS_UI_ANIM_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) + slideInVertically(
                    animationSpec = tween(
                        durationMillis = DNS_UI_ANIM_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) { fullHeight ->
                    if (switchingToManual) fullHeight / 12 else -fullHeight / 12
                }

            val exitTransition =
                fadeOut(
                    animationSpec = tween(
                        durationMillis = DNS_UI_ANIM_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) + slideOutVertically(
                    animationSpec = tween(
                        durationMillis = DNS_UI_ANIM_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) { fullHeight ->
                    if (switchingToAuto) fullHeight / 12 else -fullHeight / 12
                }

            enterTransition togetherWith exitTransition using SizeTransform(
                clip = false
            ) { _, _ ->
                tween(
                    durationMillis = DNS_UI_ANIM_DURATION,
                    easing = FastOutSlowInEasing
                )
            }
        },
        label = "ModeContent"
    ) { targetMode ->
        if (targetMode == "auto") {
            AutoRecommendationContent()
        } else {
            ManualDnsContent(
                tempUrl = tempUrl,
                onTempUrlChange = onTempUrlChange,
                showUrlValidationError = showUrlValidationError,
                testState = testState,
                onTestStateChange = onTestStateChange,
                onTestClick = coroutineScopeLaunchTest,
                sizeSpec = sizeSpec
            )
        }
    }
}

@Composable
private fun AutoRecommendationContent() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "将自动在 阿里 / 腾讯 DoH 中进行优选。",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ManualDnsContent(
    tempUrl: String,
    onTempUrlChange: (String) -> Unit,
    showUrlValidationError: Boolean,
    testState: TestState,
    onTestStateChange: (TestState) -> Unit,
    onTestClick: () -> Unit,
    sizeSpec: FiniteAnimationSpec<IntSize>
) {
    val presetListRow1 = remember {
        listOf(
            "阿里" to "https://dns.alidns.com/dns-query",
            "腾讯" to "https://doh.pub/dns-query",
            "360" to "https://doh.360.cn/dns-query"
        )
    }

    val presetListRow2 = remember {
        listOf(
            "Cloudflare" to "https://cloudflare-dns.com/dns-query",
            "Google" to "https://dns.google/dns-query",
            "Quad9" to "https://dns.quad9.net/dns-query"
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetListRow1.forEach { (name, url) ->
                    PresetButton(
                        name = name,
                        isSelected = tempUrl == url,
                        onClick = {
                            onTempUrlChange(url)
                            onTestStateChange(TestState.Idle)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetListRow2.forEach { (name, url) ->
                    PresetButton(
                        name = name,
                        isSelected = tempUrl == url,
                        onClick = {
                            onTempUrlChange(url)
                            onTestStateChange(TestState.Idle)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        OutlinedTextField(
            value = tempUrl,
            onValueChange = {
                onTempUrlChange(it.trim())
                onTestStateChange(TestState.Idle)
            },
            label = { Text("DoH URL") },
            isError = showUrlValidationError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        AnimatedVisibility(
            visible = showUrlValidationError,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = DNS_UI_ANIM_DURATION,
                    easing = FastOutSlowInEasing
                )
            ) + expandVertically(
                animationSpec = sizeSpec,
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = DNS_UI_ANIM_DURATION,
                    easing = FastOutSlowInEasing
                )
            ) + shrinkVertically(
                animationSpec = sizeSpec,
                shrinkTowards = Alignment.Top
            )
        ) {
            Text(
                text = "请输入有效的 HTTPS 地址",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        val canTest = tempUrl.isNotBlank() && tempUrl.startsWith("https://")

        OutlinedButton(
            onClick = {
                if (testState != TestState.Testing) {
                    onTestClick()
                }
            },
            enabled = canTest && testState != TestState.Testing,
            shape = RoundedCornerShape(12.dp),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 24.dp)
                .animateContentSize(
                    animationSpec = sizeSpec,
                    alignment = Alignment.TopCenter
                )
        ) {
            when (val state = testState) {
                is TestState.Success -> {
                    Text(
                        text = "✓ 连接正常",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                is TestState.Error -> {
                    Text(
                        text = "✗ ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun SegmentButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f)
        },
        animationSpec = tween(
            durationMillis = DNS_UI_ANIM_DURATION,
            easing = FastOutSlowInEasing
        ),
        label = "segmentBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(
            durationMillis = DNS_UI_ANIM_DURATION,
            easing = FastOutSlowInEasing
        ),
        label = "segmentText"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun PresetButton(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(
            durationMillis = DNS_UI_ANIM_DURATION,
            easing = FastOutSlowInEasing
        ),
        label = "presetBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(
            durationMillis = DNS_UI_ANIM_DURATION,
            easing = FastOutSlowInEasing
        ),
        label = "presetText"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            color = textColor,
            modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}