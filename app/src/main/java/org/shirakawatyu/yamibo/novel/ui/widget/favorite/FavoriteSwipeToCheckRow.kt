package org.shirakawatyu.yamibo.novel.ui.widget.favorite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import kotlin.math.roundToInt


@Composable
internal fun SwipeToCheckRow(
    enabled: Boolean,
    canConfigure: Boolean,
    onCheck: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    checkLabel: String = "检查更新",
    checkIcon: ImageVector = Icons.Default.Refresh,
    checkIconRotates: Boolean = true,
    configureLabel: String = "配置",
    configureIcon: ImageVector = Icons.Default.Build,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    val currentOnCheck by rememberUpdatedState(onCheck)
    val currentOnConfigure by rememberUpdatedState(onConfigure)

    // 触发阈值与最大可滑动距离
    val triggerPx = with(density) { 64.dp.toPx() }
    val maxLeftPx = with(density) { 96.dp.toPx() }
    val maxRightPx = if (canConfigure) with(density) { 96.dp.toPx() } else 0f
    var wasArmed by remember { mutableStateOf(false) }

    val accent = darkThemeColor(YamiboColors.primary) { primary }

    Box(modifier) {
        // 随滑动实时计算两侧揭示宽度与进度
        val leftRevealPx = (-offsetX.value).coerceAtLeast(0f)
        val rightRevealPx = if (canConfigure) offsetX.value.coerceAtLeast(0f) else 0f
        val leftProgress = (leftRevealPx / triggerPx).coerceIn(0f, 1f)
        val rightProgress = (rightRevealPx / triggerPx).coerceIn(0f, 1f)
        val leftArmed = offsetX.value <= -triggerPx
        val rightArmed = canConfigure && offsetX.value >= triggerPx
        // 面板比揭示宽度多延伸 12dp 伸到卡片圆角下面，消除缝隙
        val cornerOverlapPx = with(density) { 12.dp.toPx() }

        // 背后揭示的操作面板：与卡片同样内缩 5dp、同样 12dp 圆角，保证整体感
        Box(
            Modifier
                .matchParentSize()
                .padding(5.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (leftRevealPx > 0.5f) {
                SwipeActionPanel(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    revealPx = leftRevealPx + cornerOverlapPx,
                    progress = leftProgress,
                    armed = leftArmed,
                    icon = checkIcon,
                    label = checkLabel,
                    accent = accent,
                    iconRotation = if (checkIconRotates) leftProgress * 180f else 0f
                )
            }
            if (rightRevealPx > 0.5f) {
                SwipeActionPanel(
                    modifier = Modifier.align(Alignment.CenterStart),
                    revealPx = rightRevealPx + cornerOverlapPx,
                    progress = rightProgress,
                    armed = rightArmed,
                    icon = configureIcon,
                    label = configureLabel,
                    accent = accent,
                    iconRotation = 0f
                )
            }
        }

        // 前景：可横向拖动的收藏卡片
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(canConfigure) {
                    detectHorizontalDragGestures(
                        onDragStart = { wasArmed = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val target =
                                (offsetX.value + dragAmount).coerceIn(-maxLeftPx, maxRightPx)
                            scope.launch { offsetX.snapTo(target) }
                            val armed =
                                target <= -triggerPx || (canConfigure && target >= triggerPx)
                            if (armed && !wasArmed) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            wasArmed = armed
                        },
                        onDragEnd = {
                            val x = offsetX.value
                            scope.launch {
                                offsetX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                            }
                            when {
                                x <= -triggerPx -> currentOnCheck()
                                canConfigure && x >= triggerPx -> currentOnConfigure()
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

/**
 * 左/右滑动揭示的操作面板。
 *
 * 设计目标：与收藏卡片同高、同圆角；背景由「浅色着色 -> 实色强调色」平滑过渡，
 * 跨过触发阈值(armed)时图标弹性放大、配色翻转，给出清晰的「即将触发」反馈。
 */
@Composable
private fun SwipeActionPanel(
    revealPx: Float,
    progress: Float,
    armed: Boolean,
    icon: ImageVector,
    label: String,
    accent: Color,
    iconRotation: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val widthDp = with(density) { revealPx.toDp() }

    // armed 的弹性过渡，带一点回弹让「触发就绪」更有手感
    val arm by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "swipe_action_arm"
    )

    // 依据强调色亮度挑选对比色，避免浅色主题下白色图标看不清
    val accentLuma = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
    val onAccent = if (accentLuma > 0.6f) Color(0xFF1A1A1A) else Color.White

    val panelColor = lerp(accent.copy(alpha = 0.16f), accent, arm)
    val contentColor = lerp(accent, onAccent, arm)
    val iconScale = (0.72f + 0.28f * progress) * (1f + 0.14f * arm)
    val contentAlpha = (0.15f + progress).coerceIn(0f, 1f)
    val labelAlpha = ((widthDp.value - 46f) / 20f).coerceIn(0f, 1f)

    Box(
        modifier
            .width(widthDp)
            .fillMaxHeight()
            .background(panelColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        rotationZ = iconRotation
                    }
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = labelAlpha }
            )
        }
    }
}
