package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.util.ValueUtil

internal class LineRenderInfo(
    @JvmField val text: String,
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val letterSpacing: Float
)

internal class JustifiedLayoutCache {
    var width: Float = -1f
    var height: Float = -1f
    var text: String = ""
    var fontSize: Float = -1f
    var letterSpacing: Float = -1f
    var lineHeight: Float = -1f
    var isVertical: Boolean = false

    val renderLines = mutableListOf<LineRenderInfo>()
}

// 两端对齐算法
@Composable
fun JustifiedText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 24.sp,
    letterSpacing: TextUnit = 0.sp,
    color: Color = Color.Black,
    isVerticalMode: Boolean = false
) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            this.isAntiAlias = true
        }
    }

    // 实例化布局计算缓存
    val layoutCache = remember { JustifiedLayoutCache() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val availableWidth = size.width
            val availableHeight = size.height
            val lineHeightPx = ValueUtil.spToPx(lineHeight)
            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)

            if (layoutCache.width != availableWidth ||
                layoutCache.height != availableHeight ||
                layoutCache.text != text ||
                layoutCache.fontSize != fontSizePx ||
                layoutCache.letterSpacing != letterSpacingPx ||
                layoutCache.lineHeight != lineHeightPx ||
                layoutCache.isVertical != isVerticalMode
            ) {
                layoutCache.width = availableWidth
                layoutCache.height = availableHeight
                layoutCache.text = text
                layoutCache.fontSize = fontSizePx
                layoutCache.letterSpacing = letterSpacingPx
                layoutCache.lineHeight = lineHeightPx
                layoutCache.isVertical = isVerticalMode

                layoutCache.renderLines.clear()

                val baseLetterSpacingMultiplier =
                    if (fontSizePx > 0) letterSpacingPx / fontSizePx else 0f
                textPaint.textSize = fontSizePx
                textPaint.letterSpacing = baseLetterSpacingMultiplier

                if (isVerticalMode) {
                    // --- 竖屏模式计算 ---
                    val baselineY = (availableHeight / 2) + (fontSizePx / 2.8f)
                    var drawX = 0f
                    var textToDraw = text

                    if (text.startsWith("　　")) {
                        textToDraw = text.substring(2)
                        drawX = fontSizePx * 2f
                    }

                    val originalWidth = textPaint.measureText(textToDraw)
                    val remainingSpace = availableWidth - drawX - originalWidth
                    val isShortLine = remainingSpace > fontSizePx * 3f

                    var finalMultiplier = baseLetterSpacingMultiplier
                    if (!isShortLine && remainingSpace > 0 && textToDraw.length > 1) {
                        val gaps = textToDraw.length - 1
                        val justificationSpacingPx = remainingSpace / gaps
                        val justificationMultiplier =
                            if (fontSizePx > 0) justificationSpacingPx / fontSizePx else 0f
                        finalMultiplier = baseLetterSpacingMultiplier + justificationMultiplier
                    }

                    // 存入缓存
                    layoutCache.renderLines.add(
                        LineRenderInfo(
                            textToDraw,
                            drawX,
                            baselineY,
                            finalMultiplier
                        )
                    )

                } else {
                    // --- 横屏模式计算 ---
                    val lines = text.split('\n')

                    val validLineCount = lines.count { it.isNotEmpty() }

                    val totalStandardHeight = validLineCount * lineHeightPx
                    val safeAvailableHeight = availableHeight - (fontSizePx * 0.2f)
                    val emptySpace = (safeAvailableHeight - totalStandardHeight).coerceAtLeast(0f)

                    val extraSpacingPerLine =
                        if (validLineCount > 1 && emptySpace < lineHeightPx * 3) {
                            emptySpace / (validLineCount - 1)
                        } else {
                            0f
                        }

                    var currentY = lineHeightPx

                    for (index in lines.indices) {
                        val line = lines[index]
                        if (line.isEmpty()) {
                            continue
                        }

                        var drawX = 0f
                        var textToDraw = line

                        if (line.startsWith("　　")) {
                            textToDraw = line.substring(2)
                            drawX = fontSizePx * 2f
                        }

                        val originalWidth = textPaint.measureText(textToDraw)
                        val remainingSpace = availableWidth - drawX - originalWidth

                        val isEndOfParagraph = index < lines.lastIndex && lines[index + 1].isEmpty()
                        val isEndOfChapterShortLine =
                            index == lines.lastIndex && remainingSpace > fontSizePx * 3f
                        val skipJustification = isEndOfParagraph || isEndOfChapterShortLine

                        var finalMultiplier = baseLetterSpacingMultiplier
                        if (!skipJustification && remainingSpace > 0 && textToDraw.length > 1) {
                            val gaps = textToDraw.length - 1
                            val justificationSpacingPx = remainingSpace / gaps
                            val justificationMultiplier =
                                if (fontSizePx > 0) justificationSpacingPx / fontSizePx else 0f
                            finalMultiplier = baseLetterSpacingMultiplier + justificationMultiplier
                        }

                        // 存入缓存
                        layoutCache.renderLines.add(
                            LineRenderInfo(
                                textToDraw,
                                drawX,
                                currentY,
                                finalMultiplier
                            )
                        )
                        currentY += (lineHeightPx + extraSpacingPerLine)
                    }
                }
            }

            val nativeCanvas = drawContext.canvas.nativeCanvas
            textPaint.color = color.toArgb()
            textPaint.textSize = fontSizePx

            val lines = layoutCache.renderLines
            for (i in 0 until lines.size) {
                val info = lines[i]
                textPaint.letterSpacing = info.letterSpacing
                nativeCanvas.drawText(info.text, info.x, info.y, textPaint)
            }

            // 清理状态
            textPaint.letterSpacing = 0f
        }
    }
}