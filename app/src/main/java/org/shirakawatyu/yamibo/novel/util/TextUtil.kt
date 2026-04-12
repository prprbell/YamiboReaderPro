package org.shirakawatyu.yamibo.novel.util

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType

class TextUtil {
    companion object {
        // 避头标点集合
        private val PUNCTUATION_LINE_START_DENY_SET = BooleanArray(0x10000).apply {
            "，。,、.!？?）」)]}”\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        // 避尾标点集合
        private val PUNCTUATION_LINE_END_DENY_SET = BooleanArray(0x10000).apply {
            "（(「[{“\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        /**
         * 横屏文本分页
         */
        fun pagingText(
            text: String,
            height: Dp,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit,
            lineHeight: TextUnit,
            charRatios: FloatArray,
            typeface: Typeface // 新增：传入字体用于兜底测量
        ): List<String> {
            val targetPixelWidth = ValueUtil.dpToPx(width)
            val pageContentHeight = ValueUtil.dpToPx(height)
            val lineHeightPx = ValueUtil.spToPx(lineHeight)
            val maxLine = calculateMaxLines(pageContentHeight, lineHeightPx)

            if (maxLine <= 0 || text.isEmpty()) {
                return emptyList()
            }

            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)

            // 初始化一把专属测量的尺子，性能开销极低
            val measurePaint = Paint().apply {
                this.isAntiAlias = true
                this.textSize = fontSizePx
                this.typeface = typeface
                this.fontFeatureSettings = "\"palt\""
            }

            return performPaging(
                text = text,
                targetPixelWidth = targetPixelWidth,
                maxLine = maxLine,
                fontSizePx = fontSizePx,
                letterSpacingPx = letterSpacingPx,
                charRatios = charRatios,
                measurePaint = measurePaint
            )
        }

        private fun calculateMaxLines(totalHeightPx: Float, lineHeightPx: Float, safeAreaRatio: Float = 1.0f): Int {
            return ((totalHeightPx * safeAreaRatio) / lineHeightPx).toInt().coerceAtLeast(1)
        }

        private fun performPaging(
            text: String,
            targetPixelWidth: Float,
            maxLine: Int,
            fontSizePx: Float,
            letterSpacingPx: Float,
            charRatios: FloatArray,
            measurePaint: Paint
        ): List<String> {
            val avgCharsPerLine = (targetPixelWidth / (fontSizePx + letterSpacingPx)).toInt().coerceAtLeast(1)
            val estimatedTotalLines = (text.length / avgCharsPerLine).coerceAtLeast(1)
            val resultLines = ArrayList<String>(estimatedTotalLines)
            var isStartOfParagraph = true

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd(' ', '　', '\t', '\u00A0')

                if (line.isBlank()) {
                    if (resultLines.isNotEmpty() && resultLines.last().isNotEmpty()) {
                        resultLines.add("")
                    }
                    isStartOfParagraph = true
                } else {
                    val lineToChunk: String
                    if (isStartOfParagraph) {
                        val trimmedLine = line.trimStart(' ', '　')
                        lineToChunk = "　　$trimmedLine"
                        isStartOfParagraph = false
                    } else {
                        lineToChunk = line
                    }
                    chunkLineOptimized(
                        line = lineToChunk,
                        targetPixelWidth = targetPixelWidth,
                        fontSizePx = fontSizePx,
                        letterSpacingPx = letterSpacingPx,
                        charRatios = charRatios,
                        measurePaint = measurePaint,
                        output = resultLines
                    )
                }
            }

            val estimatedPages = (resultLines.size / maxLine).coerceAtLeast(1)
            val pages = ArrayList<String>(estimatedPages)
            var lineIndex = 0

            while (lineIndex < resultLines.size) {
                val pageBuilder = StringBuilder()
                var contentLinesOnThisPage = 0
                var lastIndexForThisPage = lineIndex

                while (lastIndexForThisPage < resultLines.size) {
                    val currentLine = resultLines[lastIndexForThisPage]
                    if (currentLine.isNotEmpty()) {
                        if (contentLinesOnThisPage >= maxLine) break
                        contentLinesOnThisPage++
                    }
                    lastIndexForThisPage++
                }

                for (k in lineIndex until lastIndexForThisPage) {
                    pageBuilder.append(resultLines[k])
                    if (k < lastIndexForThisPage - 1) {
                        pageBuilder.append('\n')
                    }
                }

                if (pageBuilder.isNotEmpty()) {
                    pages.add(pageBuilder.toString())
                }
                lineIndex = lastIndexForThisPage
            }

            return pages
        }

        private fun chunkLineOptimized(
            line: String,
            targetPixelWidth: Float,
            fontSizePx: Float,
            letterSpacingPx: Float,
            charRatios: FloatArray,
            measurePaint: Paint,
            output: MutableList<String>
        ) {
            val lineLength = line.length
            var startIndex = 0
            var i = 0
            var currentWidth = 0.0f

            while (i < lineLength) {
                val c = line[i]
                val code = c.code

                // O(1) 懒加载测量机制
                var ratio = if (code < 65536) charRatios[code] else 1.0f
                if (ratio < 0f) {
                    // 只有未测量的字符才会走进这里，测量并永久缓存
                    ratio = measurePaint.measureText(c.toString()) / fontSizePx
                    if (code < 65536) charRatios[code] = ratio
                }

                // 修正：正确的字宽计算公式 -> (比例 * 字号) + 字间距
                val charWidth = (ratio * fontSizePx) + letterSpacingPx

                if (currentWidth + charWidth > targetPixelWidth && i > startIndex) {
                    val lastChar = line[i - 1]

                    if (code < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[code]) {
                        output.add(line.substring(startIndex, i + 1))
                        startIndex = i + 1
                        i = startIndex
                        currentWidth = 0.0f
                        continue
                    }

                    if (lastChar.code < PUNCTUATION_LINE_END_DENY_SET.size && PUNCTUATION_LINE_END_DENY_SET[lastChar.code]) {
                        if (i - 1 > startIndex) {
                            output.add(line.substring(startIndex, i - 1))
                            startIndex = i - 1
                            i = startIndex
                            currentWidth = 0.0f
                            continue
                        }
                    }

                    output.add(line.substring(startIndex, i))
                    startIndex = i
                    currentWidth = 0.0f
                } else {
                    currentWidth += charWidth
                    i++
                }
            }

            if (startIndex < lineLength) {
                output.add(line.substring(startIndex, lineLength))
            }
        }

        /**
         * 竖屏文本分页
         */
        fun pagingTextVertical(
            rawContentList: List<Content>,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit,
            charRatios: FloatArray,
            typeface: Typeface // 新增：传入字体用于兜底测量
        ): List<Content> {
            val targetPixelWidth = ValueUtil.dpToPx(width)
            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)

            val measurePaint = Paint().apply {
                this.isAntiAlias = true
                this.textSize = fontSizePx
                this.typeface = typeface
            }

            val resultLines = ArrayList<Content>()
            var isStartOfParagraph = true

            for (content in rawContentList) {
                if (content.type == ContentType.IMG) {
                    resultLines.add(content)
                    isStartOfParagraph = true
                    continue
                }

                if (content.type == ContentType.TEXT) {
                    val text = content.data
                    val chapterTitle = content.chapterTitle

                    text.lineSequence().forEach { rawLine ->
                        val line = rawLine.trimEnd(' ', '　', '\t', '\u00A0')

                        if (line.isBlank()) {
                            if (resultLines.isNotEmpty()) {
                                val lastContent = resultLines.last()
                                if (lastContent.type != ContentType.TEXT || lastContent.data.isNotEmpty()) {
                                    resultLines.add(Content("", ContentType.TEXT, chapterTitle))
                                }
                            }
                            isStartOfParagraph = true
                        } else {
                            val lineToChunk: String
                            if (isStartOfParagraph) {
                                val trimmedLine = line.trimStart(' ', '　')
                                lineToChunk = "　　$trimmedLine"
                                isStartOfParagraph = false
                            } else {
                                lineToChunk = line
                            }

                            chunkLineOptimizedVertical(
                                line = lineToChunk,
                                targetPixelWidth = targetPixelWidth,
                                fontSizePx = fontSizePx,
                                letterSpacingPx = letterSpacingPx,
                                charRatios = charRatios,
                                measurePaint = measurePaint,
                                chapterTitle = chapterTitle,
                                output = resultLines
                            )
                        }
                    }
                }
            }
            return resultLines
        }

        private fun chunkLineOptimizedVertical(
            line: String,
            targetPixelWidth: Float,
            fontSizePx: Float,
            letterSpacingPx: Float,
            charRatios: FloatArray,
            measurePaint: Paint,
            chapterTitle: String?,
            output: MutableList<Content>
        ) {
            val lineLength = line.length
            var startIndex = 0
            var i = 0
            var currentWidth = 0.0f

            while (i < lineLength) {
                val c = line[i]
                val code = c.code

                // O(1) 懒加载测量机制
                var ratio = if (code < 65536) charRatios[code] else 1.0f
                if (ratio < 0f) {
                    ratio = measurePaint.measureText(c.toString()) / fontSizePx
                    if (code < 65536) charRatios[code] = ratio
                }

                // 修正：正确的字宽计算公式
                val charWidth = (ratio * fontSizePx) + letterSpacingPx

                if (currentWidth + charWidth > targetPixelWidth && i > startIndex) {
                    val lastChar = line[i - 1]

                    if (code < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[code]) {
                        output.add(Content(line.substring(startIndex, i + 1), ContentType.TEXT, chapterTitle))
                        startIndex = i + 1
                        i = startIndex
                        currentWidth = 0.0f
                        continue
                    }

                    if (lastChar.code < PUNCTUATION_LINE_END_DENY_SET.size && PUNCTUATION_LINE_END_DENY_SET[lastChar.code]) {
                        if (i - 1 > startIndex) {
                            output.add(Content(line.substring(startIndex, i - 1), ContentType.TEXT, chapterTitle))
                            startIndex = i - 1
                            i = startIndex
                            currentWidth = 0.0f
                            continue
                        }
                    }

                    output.add(Content(line.substring(startIndex, i), ContentType.TEXT, chapterTitle))
                    startIndex = i
                    currentWidth = 0.0f
                } else {
                    currentWidth += charWidth
                    i++
                }
            }

            if (startIndex < lineLength) {
                output.add(Content(line.substring(startIndex, lineLength), ContentType.TEXT, chapterTitle))
            }
        }
    }
}