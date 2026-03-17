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
    // 创建Paint对象
    val textPaint = remember {
        android.graphics.Paint().apply {
            this.isAntiAlias = true
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. 获取画布和参数
            // 可用宽度即是画布的宽度（父 Composable 已经处理了边距）
            val availableWidth = size.width
            val lineHeightPx = ValueUtil.spToPx(lineHeight)
            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)

            // 2. 配置 Paint
            textPaint.color = color.toArgb()
            textPaint.textSize = fontSizePx
            // 计算并设置基础字间距（Paint 的 letterSpacing 是一个乘数）
            val baseLetterSpacingMultiplier =
                if (fontSizePx > 0) letterSpacingPx / fontSizePx else 0f
            textPaint.letterSpacing = baseLetterSpacingMultiplier


            // 模式切换
            if (isVerticalMode) {
                // --- 竖屏模式 ---
                // text 是单行，Y 坐标固定
                // 在ContentViewer中已经将Composable高度设为lineHeightPx，
                // 计算基线(baseline)使其在行高内垂直居中
                val baselineY = (size.height / 2) + (fontSizePx / 2.8f) // 粗略估算

                // 剥离缩进字符，使用绝对坐标锚定缩进
                var drawX = 0f
                var textToDraw = text

                // 如果这行是以两个全角空格开头（说明是段落首行）
                if (text.startsWith("　　")) {
                    // 截掉前两个空格，只画后面的纯文字
                    textToDraw = text.substring(2)
                    // 强制把起点往后推正好 2 个字的像素宽度（在竖排视觉中等于往下推）
                    drawX = fontSizePx * 2f
                }

                // 重置为基础间距，用于测量真实文字宽度
                textPaint.letterSpacing = baseLetterSpacingMultiplier
                val originalWidth = textPaint.measureText(textToDraw)

                // 剩余空间 = 可用总长 - 固定的缩进距离 - 文字本身的宽度
                val remainingSpace = availableWidth - drawX - originalWidth

                // 竖屏对齐与短行拦截逻辑
                // 在单行传入模式下，如果剩余空间大于 3 个全角字符的宽度，
                // 那它100%是段落或者章节的最后一行短句，坚决不对齐。
                val isShortLine = remainingSpace > fontSizePx * 3f

                // 如果不是短行，且有剩余空间，且行内多于1个字，执行两端对齐
                if (!isShortLine && remainingSpace > 0 && textToDraw.length > 1) {
                    val gaps = textToDraw.length - 1
                    // 计算需要分配到每个间隙的额外像素
                    val justificationSpacingPx = remainingSpace / gaps
                    // 转换为 Paint 的乘数
                    val justificationMultiplier =
                        if (fontSizePx > 0) justificationSpacingPx / fontSizePx else 0f

                    // 应用总间距（基础间距 + 对齐间距）
                    textPaint.letterSpacing = baseLetterSpacingMultiplier + justificationMultiplier
                }
                // else: 保持基础间距 (已设置)

                // 绘制文本
                drawContext.canvas.nativeCanvas.drawText(
                    textToDraw,
                    drawX, // X = 计算后的绝对缩进坐标（非缩进行则为 0f）
                    baselineY, // Y=居中基线
                    textPaint
                )

            } else {
                // --- 横屏模式 ---
                // 3. 按 TextUtil 的换行符 \n 拆分
                val lines = text.split('\n')
                var currentY = lineHeightPx // Y 坐标从第一行的基线开始

                // 4. 逐行绘制
                lines.forEachIndexed { index, line ->
                    // 如果是空行（段落分隔），只增加 Y 坐标
                    if (line.isEmpty()) {
                        return@forEachIndexed
                    }

                    // 剥离缩进字符，使用绝对坐标锚定缩进
                    var drawX = 0f
                    var textToDraw = line

                    // 如果这行是以两个全角空格开头（说明是段落首行）
                    if (line.startsWith("　　")) {
                        // 截掉前两个空格，只画后面的纯文字
                        textToDraw = line.substring(2)
                        // 强制把 X 起点往右推正好 2 个字的像素宽度
                        drawX = fontSizePx * 2f
                    }

                    // 重置为基础间距，用于测量真实的文字宽度
                    textPaint.letterSpacing = baseLetterSpacingMultiplier
                    val originalWidth = textPaint.measureText(textToDraw)
                    // 剩余空间 = 画布总宽 - 固定的缩进X坐标 - 文字本身的宽度
                    val remainingSpace = availableWidth - drawX - originalWidth

                    // 1. 是否是普通段落的最后一行
                    val isEndOfParagraph = index < lines.lastIndex && lines[index + 1].isEmpty()
                    // 2. 是否是真正的章节末尾短句（基于剩余空间判断）
                    val isEndOfChapterShortLine =
                        index == lines.lastIndex && remainingSpace > fontSizePx * 3f

                    val skipJustification = isEndOfParagraph || isEndOfChapterShortLine

                    // 5. 实现两端对齐（Justification）
                    // 注意这里改为判断 textToDraw.length
                    if (!skipJustification && remainingSpace > 0 && textToDraw.length > 1) {
                        val gaps = textToDraw.length - 1
                        // 计算需要分配到每个间隙的额外像素
                        val justificationSpacingPx = remainingSpace / gaps
                        val justificationMultiplier =
                            if (fontSizePx > 0) justificationSpacingPx / fontSizePx else 0f

                        // 应用总间距（基础间距 + 对齐间距）
                        textPaint.letterSpacing =
                            baseLetterSpacingMultiplier + justificationMultiplier
                    }

                    // 6. 绘制文本
                    // 注意：这里传入的是剔除了空格的 textToDraw，以及计算好的 drawX
                    drawContext.canvas.nativeCanvas.drawText(
                        textToDraw,
                        drawX,
                        currentY,
                        textPaint
                    )

                    currentY += lineHeightPx
                }
            }

            // 清理Paint状态
            textPaint.letterSpacing = 0f
        }
    }
}