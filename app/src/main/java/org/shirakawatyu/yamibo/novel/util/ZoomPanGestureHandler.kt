package org.shirakawatyu.yamibo.novel.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 竖屏漫画阅读模式的缩放平移手势处理器
 */
class ZoomPanGestureHandler(
    val scale: Animatable<Float, AnimationVector1D>,
    val offsetX: Animatable<Float, AnimationVector1D>,
    val offsetY: Animatable<Float, AnimationVector1D>,
    private val screenWidthPx: Float,
    private val screenHeightPx: Float,
    private val scaleRange: ClosedFloatingPointRange<Float> = 1f..4f,
    private val zoomDamping: Float = 0.65f,
    private val doubleTapScale: Float = 2.0f,
    private val rubberBandFactor: Float = 0.3f,
    private val flingVelocityThreshold: Float = 50f,
    private val flingFriction: Float = 1.75f,
) {

    val isZoomed: Boolean get() = scale.value > 1.01f

    // ========================= 边界计算 =========================

    private fun maxOffsetX(s: Float = scale.value): Float =
        (screenWidthPx * (s - 1f)) / 2f

    fun maxOffsetY(s: Float = scale.value): Float =
        (screenHeightPx * (s - 1f)) / 2f

    private fun clampOffset(x: Float, y: Float, s: Float = scale.value): Offset {
        val mx = maxOffsetX(s)
        val my = maxOffsetY(s)
        return Offset(x.coerceIn(-mx, mx), y.coerceIn(-my, my))
    }

    private fun rubberBand(value: Float, min: Float, max: Float): Float = when {
        value < min -> min + (value - min) * rubberBandFactor
        value > max -> max + (value - max) * rubberBandFactor
        else -> value
    }

    // ========================= 双指缩放 =========================

    suspend fun handlePinchZoom(zoomChange: Float, panChange: Offset) {
        val oldScale = scale.value
        val dampenedZoom = 1f + (zoomChange - 1f) * zoomDamping
        val newScale = (oldScale * dampenedZoom).coerceIn(scaleRange)
        val scaleDelta = newScale / oldScale

        val rawX = offsetX.value * scaleDelta + panChange.x
        val rawY = offsetY.value * scaleDelta + panChange.y

        scale.snapTo(newScale)
        if (newScale > 1f) {
            val clamped = clampOffset(rawX, rawY, newScale)
            offsetX.snapTo(clamped.x)
            offsetY.snapTo(clamped.y)
        } else {
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    // ========================= 单指平移 (仅限 X 轴) =========================

    /**
     * Y 轴全权交给了 LazyColumn，这里只负责 X 轴的橡皮筋拖拽
     */
    suspend fun handlePanX(panX: Float) {
        if (!isZoomed || panX == 0f) return
        val mx = maxOffsetX()
        val rawX = offsetX.value + panX
        offsetX.snapTo(rubberBand(rawX, -mx, mx))
    }

    // ========================= 回弹 & Fling =========================

    suspend fun snapBackIfNeeded() = coroutineScope {
        val mx = maxOffsetX()
        val targetX = offsetX.value.coerceIn(-mx, mx)
        if (abs(targetX - offsetX.value) > 0.5f) {
            launch { offsetX.animateTo(targetX, tween(250)) }
        }
    }

    /**
     * Y 轴的惯性交给 LazyColumn 原生处理，我们只接管 X 轴的阻尼衰减
     */
    suspend fun flingX(velocityX: Float) {
        val speed = abs(velocityX)
        if (speed < flingVelocityThreshold) {
            snapBackIfNeeded()
            return
        }

        val mx = maxOffsetX()
        val decaySpec = exponentialDecay<Float>(frictionMultiplier = flingFriction)

        coroutineScope {
            launch {
                offsetX.updateBounds(-mx, mx)
                offsetX.animateDecay(velocityX, decaySpec)
            }
        }
    }

    // ========================= 双击缩放 =========================

    suspend fun doubleTapToggle(tapOffset: Offset) = coroutineScope {
        if (isZoomed) {
            launch { scale.animateTo(1f, tween(300)) }
            launch { offsetX.animateTo(0f, tween(300)) }
            launch { offsetY.animateTo(0f, tween(300)) }
        } else {
            val ts = doubleTapScale
            val cx = screenWidthPx / 2f
            val cy = screenHeightPx / 2f
            val tx = (cx - tapOffset.x) * (ts - 1f)
            val ty = (cy - tapOffset.y) * (ts - 1f)
            val clamped = clampOffset(tx, ty, ts)

            launch { scale.animateTo(ts, tween(300)) }
            launch { offsetX.animateTo(clamped.x, tween(300)) }
            launch { offsetY.animateTo(clamped.y, tween(300)) }
        }
    }

    suspend fun maybeResetIfNoZoom() {
        if (scale.value <= 1.01f) {
            coroutineScope {
                launch { scale.animateTo(1f, tween(200)) }
                launch { offsetX.animateTo(0f, tween(200)) }
                launch { offsetY.animateTo(0f, tween(200)) }
            }
        }
    }
}

// ========================= Modifier 扩展 =========================
private class TapSuppressionState {
    var isSuppressed = false
}
/**
 * 竖屏漫画阅读模式的缩放+平移手势 Modifier
 */
fun Modifier.verticalMangaZoomGesture(
    handler: ZoomPanGestureHandler,
    scope: CoroutineScope,
    onTap: () -> Unit = {},
    enabled: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this

    val nestedScrollConnection = remember(handler) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!handler.isZoomed) return Offset.Zero

                val deltaY = available.y
                val currentOffsetY = handler.offsetY.value

                if (currentOffsetY > 0.01f && deltaY < 0f) {
                    val consumedY = deltaY.coerceAtLeast(-currentOffsetY)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handler.offsetY.snapTo(currentOffsetY + consumedY)
                    }
                    return Offset(0f, consumedY)
                }
                if (currentOffsetY < -0.01f && deltaY > 0f) {
                    val consumedY = deltaY.coerceAtMost(-currentOffsetY)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handler.offsetY.snapTo(currentOffsetY + consumedY)
                    }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!handler.isZoomed) return Offset.Zero

                val unconsumedY = available.y
                if (abs(unconsumedY) > 0.01f) {
                    val currentOffsetY = handler.offsetY.value
                    val maxY = handler.maxOffsetY()
                    val targetY = (currentOffsetY + unconsumedY).coerceIn(-maxY, maxY)
                    val actualConsumed = targetY - currentOffsetY

                    if (abs(actualConsumed) > 0.01f) {
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            handler.offsetY.snapTo(targetY)
                        }
                        return Offset(0f, actualConsumed)
                    }
                }
                return Offset.Zero
            }
        }
    }

    // “另辟蹊径”：用来判断当前手势到底是不是真正的滑动，如果是，就屏蔽接下来的 Tap
    val tapState = remember { TapSuppressionState() }

    this
        .nestedScroll(nestedScrollConnection)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                val velocityTracker = VelocityTracker()
                var wasMultiTouch = false
                var totalPanDistance = 0f
                var wasEmpty = true

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }

                    // 一旦手指全新落下（从 0 到 1），重置累计距离和屏蔽状态
                    if (wasEmpty && pressed.isNotEmpty()) {
                        totalPanDistance = 0f
                        tapState.isSuppressed = false
                    }

                    // 收集速度样本
                    event.changes.forEach { change ->
                        if (change.pressed && change.positionChanged()) {
                            velocityTracker.addPointerInputChange(change)
                        }
                    }

                    if (pressed.size > 1) {
                        // ---- 双指缩放 ----
                        wasMultiTouch = true
                        tapState.isSuppressed = true // 多指操作绝不触发普通点击
                        velocityTracker.resetTracking()

                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        if (zoom != 1f || pan != Offset.Zero) {
                            // 缩放时拦截所有事件，防止底层列表跟着瞎抖
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                handler.handlePinchZoom(zoom, pan)
                            }
                        }
                    } else if (pressed.size == 1) {
                        // ---- 单指追踪 ----
                        val pan = event.calculatePan()
                        if (pan != Offset.Zero) {
                            totalPanDistance += pan.getDistance()

                            // 【核心判断】：只要单指滑动的总距离超过了系统的防误触阈值，这局游戏就不是点击！屏蔽它！
                            if (totalPanDistance > viewConfiguration.touchSlop) {
                                tapState.isSuppressed = true
                            }

                            // 仅在缩放状态下处理图片的 X 轴移动
                            if (handler.isZoomed) {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    handler.handlePanX(pan.x)
                                }
                            }
                        }
                        // 放行所有的 Y 轴变化，让底层的 LazyColumn 自由滑动。
                    } else if (pressed.isEmpty()) {
                        // ---- 所有手指抬起 ----
                        if (handler.isZoomed && !wasMultiTouch) {
                            val velocity = velocityTracker.calculateVelocity()
                            scope.launch {
                                // 松手时只做 X 轴的惯性计算，Y 轴由 LazyColumn 的原生 Fling 接管
                                handler.flingX(velocity.x)
                            }
                        } else if (wasMultiTouch) {
                            scope.launch { handler.maybeResetIfNoZoom() }
                        }

                        wasMultiTouch = false
                        velocityTracker.resetTracking()
                    }

                    // 更新为下一次循环做准备
                    wasEmpty = pressed.isEmpty()
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { tapOffset ->
                    if (!tapState.isSuppressed) {
                        scope.launch { handler.doubleTapToggle(tapOffset) }
                    }
                },
                onTap = {
                    if (!tapState.isSuppressed) {
                        onTap()
                    }
                }
            )
        }
}