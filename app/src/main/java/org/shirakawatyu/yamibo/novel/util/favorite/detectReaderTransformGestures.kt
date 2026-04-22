package org.shirakawatyu.yamibo.novel.util.favorite

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * 阅读器手势探测器（扩展函数）
 * 用于检测缩放和平移手势，并根据缩放状态和多指情况决定是否消费事件
 * 避免与底层Pager滚动冲突
 * @param scaleProvider 提供当前缩放比例的lambda，用于判断是否需要拦截
 * @param onGesture 缩放/平移时的回调
 */
suspend fun PointerInputScope.detectReaderTransformGestures(
    scaleProvider: () -> Float,
    onGesture: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val panMotion = pan.getDistance()

                    val isZoomed = scaleProvider() > 1f
                    val isMultiTouch = event.changes.size > 1

                    // 核心拦截逻辑：只有发生实质性缩放，或在放大/多指状态下发生拖拽，才判定跨越阈值
                    if (zoomMotion > touchSlop || (panMotion > touchSlop && (isZoomed || isMultiTouch))) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    val isZoomed = scaleProvider() > 1f
                    val isMultiTouch = event.changes.size > 1

                    // 只有放大或双指时，才真正消耗掉坐标偏移量，不让底层的 Pager 收到
                    if (isZoomed || isMultiTouch) {
                        event.changes.forEach {
                            if (it.positionChanged()) {
                                it.consume()
                            }
                        }
                    }
                    // 回调给上层处理缩放或平移 UI
                    onGesture(panChange, zoomChange)
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}