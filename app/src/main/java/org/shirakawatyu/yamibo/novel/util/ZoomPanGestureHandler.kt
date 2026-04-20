package org.shirakawatyu.yamibo.novel.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay // 修改点：导入原生样条曲线衰减
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
import androidx.compose.ui.platform.LocalDensity // 修改点：导入 LocalDensity
import androidx.compose.ui.unit.Density // 修改点：导入 Density
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
) {

    val isZoomed: Boolean get() = scale.value > 1.01f

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


    suspend fun handlePanX(panX: Float) {
        if (!isZoomed || panX == 0f) return
        val mx = maxOffsetX()
        val rawX = offsetX.value + panX
        offsetX.snapTo(rubberBand(rawX, -mx, mx))
    }

    suspend fun snapBackIfNeeded() = coroutineScope {
        val mx = maxOffsetX()
        val targetX = offsetX.value.coerceIn(-mx, mx)
        if (abs(targetX - offsetX.value) > 0.5f) {
            launch { offsetX.animateTo(targetX, tween(250)) }
        }
    }


    suspend fun flingX(velocityX: Float, density: Density) {
        val speed = abs(velocityX)
        if (speed < flingVelocityThreshold) {
            snapBackIfNeeded()
            return
        }

        val mx = maxOffsetX()
        val decaySpec = splineBasedDecay<Float>(density)

        coroutineScope {
            launch {
                offsetX.updateBounds(-mx, mx)
                offsetX.animateDecay(velocityX, decaySpec)
            }
        }
    }

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

    val density = LocalDensity.current

    val nestedScrollConnection = remember(handler) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!handler.isZoomed) return Offset.Zero

                var consumedY = 0f
                val scale = handler.scale.value

                if (scale > 1f) {
                    val speedReductionY = available.y * (1f - 1f / scale)
                    consumedY += speedReductionY
                }

                val deltaY = available.y - consumedY
                val currentOffsetY = handler.offsetY.value

                if (currentOffsetY > 0.01f && deltaY < 0f) {
                    val extraConsumeY = deltaY.coerceAtLeast(-currentOffsetY)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handler.offsetY.snapTo(currentOffsetY + extraConsumeY)
                    }
                    consumedY += extraConsumeY
                } else if (currentOffsetY < -0.01f && deltaY > 0f) {
                    val extraConsumeY = deltaY.coerceAtMost(-currentOffsetY)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        handler.offsetY.snapTo(currentOffsetY + extraConsumeY)
                    }
                    consumedY += extraConsumeY
                }

                return Offset(0f, consumedY)
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

                    if (wasEmpty && pressed.isNotEmpty()) {
                        totalPanDistance = 0f
                        tapState.isSuppressed = false
                    }

                    event.changes.forEach { change ->
                        if (change.pressed && change.positionChanged()) {
                            velocityTracker.addPointerInputChange(change)
                        }
                    }

                    if (pressed.size > 1) {
                        wasMultiTouch = true
                        tapState.isSuppressed = true
                        velocityTracker.resetTracking()

                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        if (zoom != 1f || pan != Offset.Zero) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                handler.handlePinchZoom(zoom, pan)
                            }
                        }
                    } else if (pressed.size == 1) {
                        val pan = event.calculatePan()
                        if (pan != Offset.Zero) {
                            totalPanDistance += pan.getDistance()

                            if (totalPanDistance > viewConfiguration.touchSlop) {
                                tapState.isSuppressed = true
                            }

                            if (handler.isZoomed) {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    handler.handlePanX(pan.x)
                                }
                            }
                        }
                    } else if (pressed.isEmpty()) {
                        if (handler.isZoomed && !wasMultiTouch) {
                            val velocity = velocityTracker.calculateVelocity()
                            scope.launch {
                                // 修改点 4：在这里将 density 传入 flingX，以确保动画能够使用正确的物理特性
                                handler.flingX(velocity.x, density)
                            }
                        } else if (wasMultiTouch) {
                            scope.launch { handler.maybeResetIfNoZoom() }
                        }

                        wasMultiTouch = false
                        velocityTracker.resetTracking()
                    }

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