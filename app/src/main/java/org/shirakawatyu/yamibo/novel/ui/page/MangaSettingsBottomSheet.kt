package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val BgSheet = Color(0xFF111318)
private val BgItem = Color(0xFF1C2028)
private val Accent = Color(0xE5DB562A)
private val TextPri = Color(0xE5E0DCD6)

@Composable
fun MangaSettingsPanel(
    modifier: Modifier = Modifier,
    currentMode: Int,
    brightness: Float,
    onModeChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val offsetY = remember { Animatable(2000f) }
    val scrimAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        launch { offsetY.animateTo(0f, animationSpec = tween(350)) }
        launch { scrimAlpha.animateTo(0.6f, animationSpec = tween(350)) }
    }

    fun dismiss() {
        scope.launch {
            launch { offsetY.animateTo(2000f, tween(350)) }
            launch { scrimAlpha.animateTo(0f, tween(350)) }.join()
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha.value))
                .clickable { dismiss() }
        )

        // 底部内容面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .offset(y = offsetY.value.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(BgSheet)
                .padding(bottom = 96.dp)
        ) {
            // 顶部拖拽条及事件拦截
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (offsetY.value > 120f) dismiss()
                                    else {
                                        offsetY.animateTo(
                                            0f, spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetY.animateTo(
                                        0f, spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                dragJob?.cancel()
                                dragJob = scope.launch {
                                    val next = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                    offsetY.snapTo(next)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF404655))
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "设置",
                            color = TextPri,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 设置项：同行排列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "亮度",
                    color = TextPri,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(28.dp)) // 留出一点间距
                androidx.compose.material3.Slider(
                    value = brightness,
                    valueRange = 0.20f..1f, // 最暗0.20，最亮1f
                    onValueChange = onBrightnessChange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = BgItem
                    )
                )
            }

            Spacer(Modifier.height(32.dp))

            // 2. 阅读方向设置行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "阅读方向",
                    color = TextPri,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val modes = listOf("下滑", "左滑", "右滑")
                    modes.forEachIndexed { index, title ->
                        val isSelected = currentMode == index
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Accent else BgItem)
                                .clickable { onModeChange(index) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) Color(0xFF111318) else TextPri,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}