package org.shirakawatyu.yamibo.novel.util.reader

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType
import org.shirakawatyu.yamibo.novel.util.reader.ValueUtil

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
            typeface: Typeface
        ): List<String> {
            val targetPixelWidth = ValueUtil.Companion.dpToPx(width)
            val pageContentHeight = ValueUtil.Companion.dpToPx(height)
            val lineHeightPx = ValueUtil.Companion.spToPx(lineHeight)
            val maxLine = calculateMaxLines(pageContentHeight, lineHeightPx)

            if (maxLine <= 0 || text.isEmpty()) {
                return emptyList()
            }

            val fontSizePx = ValueUtil.Companion.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.Companion.spToPx(letterSpacing)

            val measurePaint = Paint().apply {
                this.isAntiAlias = true
                this.textSize = fontSizePx
                this.typeface = typeface
                this.fontFeatureSettings = "\"palt\""
                this.isSubpixelText = true
                this.isLinearText = true
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

            while (startIndex < lineLength) {
                var endIndex = startIndex + 1
                var totalWidth = 0f

                while (endIndex <= lineLength) {
                    val textWidth = measurePaint.measureText(line, startIndex, endIndex)
                    totalWidth = textWidth + (endIndex - startIndex) * letterSpacingPx

                    if (totalWidth > targetPixelWidth) {
                        endIndex--
                        break
                    }
                    endIndex++
                }

                if (endIndex <= startIndex) {
                    endIndex = startIndex + 1
                } else if (endIndex < lineLength) {
                    var splitIndex = endIndex

                    var currentNextChar = line[splitIndex].code
                    var isNextStartDeny = currentNextChar < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[currentNextChar]

                    if (isNextStartDeny) {
                        while (isNextStartDeny) {
                            splitIndex++
                            if (splitIndex < lineLength) {
                                currentNextChar = line[splitIndex].code
                                isNextStartDeny = currentNextChar < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[currentNextChar]
                            } else {
                                break
                            }
                        }
                    } else {
                        while (splitIndex > startIndex + 1) {
                            val lastChar = line[splitIndex - 1].code
                            val isCurrentEndDeny = lastChar < PUNCTUATION_LINE_END_DENY_SET.size && PUNCTUATION_LINE_END_DENY_SET[lastChar]

                            if (isCurrentEndDeny) {
                                splitIndex--
                            } else {
                                break
                            }
                        }
                    }
                    endIndex = splitIndex
                } else {
                    endIndex = lineLength
                }

                output.add(line.substring(startIndex, endIndex))
                startIndex = endIndex
                while (startIndex < lineLength && line[startIndex] == ' ') {
                    startIndex++
                }
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
            typeface: Typeface
        ): List<Content> {
            val targetPixelWidth = ValueUtil.Companion.dpToPx(width)
            val fontSizePx = ValueUtil.Companion.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.Companion.spToPx(letterSpacing)

            val measurePaint = Paint().apply {
                this.isAntiAlias = true
                this.textSize = fontSizePx
                this.typeface = typeface
                this.fontFeatureSettings = "\"palt\""
                this.isSubpixelText = true
                this.isLinearText = true
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

            while (startIndex < lineLength) {
                var endIndex = startIndex + 1

                while (endIndex <= lineLength) {
                    val textWidth = measurePaint.measureText(line, startIndex, endIndex)
                    val totalWidth = textWidth + (endIndex - startIndex) * letterSpacingPx

                    if (totalWidth > targetPixelWidth) {
                        endIndex--
                        break
                    }
                    endIndex++
                }

                if (endIndex <= startIndex) {
                    endIndex = startIndex + 1
                } else if (endIndex < lineLength) {
                    var splitIndex = endIndex

                    while (splitIndex > startIndex + 1) {
                        val nextChar = line[splitIndex].code
                        val lastChar = line[splitIndex - 1].code

                        val isNextStartDeny = nextChar < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[nextChar]
                        val isCurrentEndDeny = lastChar < PUNCTUATION_LINE_END_DENY_SET.size && PUNCTUATION_LINE_END_DENY_SET[lastChar]

                        if (isNextStartDeny || isCurrentEndDeny) {
                            splitIndex--
                        } else {
                            break
                        }
                    }
                    endIndex = splitIndex
                } else {
                    endIndex = lineLength
                }

                output.add(
                    Content(
                        line.substring(startIndex, endIndex),
                        ContentType.TEXT,
                        chapterTitle
                    )
                )
                startIndex = endIndex
                while (startIndex < lineLength && line[startIndex] == ' ') {
                    startIndex++
                }
            }
        }
    }
}