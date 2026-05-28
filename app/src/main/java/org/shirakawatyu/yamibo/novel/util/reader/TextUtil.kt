package org.shirakawatyu.yamibo.novel.util.reader

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType

class TextUtil {
    companion object {
        // 避头标点集合：这些字符不应出现在新行开头。
        private val PUNCTUATION_LINE_START_DENY_SET = BooleanArray(0x10000).apply {
            "，。,、.!？?）」)]}”\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        // 避尾标点集合：这些字符不应出现在上一行结尾。
        private val PUNCTUATION_LINE_END_DENY_SET = BooleanArray(0x10000).apply {
            "（(「[{“\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        private val TRIM_CHARS = charArrayOf(' ', '　', '\t', '\u00A0', '\u200B')

        /**
         * 横屏文本分页
         * */
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
            val targetPixelWidth = ValueUtil.dpToPx(width)
            val pageContentHeight = ValueUtil.dpToPx(height)
            val lineHeightPx = ValueUtil.spToPx(lineHeight)
            val maxLine = calculateMaxLines(pageContentHeight, lineHeightPx)

            if (maxLine <= 0 || text.isEmpty()) {
                return emptyList()
            }

            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)

            val measurePaint = createMeasurePaint(fontSizePx, typeface)

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

        private fun createMeasurePaint(fontSizePx: Float, typeface: Typeface): Paint {
            return Paint().apply {
                isAntiAlias = true
                textSize = fontSizePx
                this.typeface = typeface
                fontFeatureSettings = "\"palt\""
                isSubpixelText = true
                isLinearText = true
            }
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
            @Suppress("UNUSED_PARAMETER") charRatios: FloatArray,
            measurePaint: Paint
        ): List<String> {
            val avgCharsPerLine = (targetPixelWidth / (fontSizePx + letterSpacingPx)).toInt().coerceAtLeast(1)
            val estimatedTotalLines = (text.length / avgCharsPerLine).coerceAtLeast(1)
            val resultLines = ArrayList<String>(estimatedTotalLines)
            var isStartOfParagraph = true

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd(*TRIM_CHARS)

                if (line.isBlank()) {
                    if (resultLines.isNotEmpty() && resultLines.last().isNotEmpty()) {
                        resultLines.add("")
                    }
                    isStartOfParagraph = true
                } else {
                    val lineToChunk = if (isStartOfParagraph) {
                        val trimmedLine = line.trimStart(*TRIM_CHARS)
                        isStartOfParagraph = false
                        "　　$trimmedLine"
                    } else {
                        line
                    }

                    chunkLineHorizontalExact(
                        line = lineToChunk,
                        targetPixelWidth = targetPixelWidth,
                        letterSpacingPx = letterSpacingPx,
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

        private fun chunkLineHorizontalExact(
            line: String,
            targetPixelWidth: Float,
            letterSpacingPx: Float,
            measurePaint: Paint,
            output: MutableList<String>
        ) {
            val lineLength = line.length
            var startIndex = 0

            while (startIndex < lineLength) {
                var endIndex = findMaxEndIndexByBinarySearch(
                    line = line,
                    startIndex = startIndex,
                    targetPixelWidth = targetPixelWidth,
                    letterSpacingPx = letterSpacingPx,
                    measurePaint = measurePaint
                )

                if (endIndex < lineLength) {
                    endIndex = adjustBreakForHorizontalPunctuation(
                        line = line,
                        startIndex = startIndex,
                        candidateEndIndex = endIndex
                    )
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
         * 用与旧实现完全相同的宽度判定做二分：
         * Paint.measureText(substring) + 字符数 * letterSpacingPx <= targetPixelWidth。
         *
         * 这不是 charRatios 估算，也不是 Paint.breakText，因此不会改变你原来偏保守的排版边界。
         */
        private fun findMaxEndIndexByBinarySearch(
            line: String,
            startIndex: Int,
            targetPixelWidth: Float,
            letterSpacingPx: Float,
            measurePaint: Paint
        ): Int {
            val lineLength = line.length
            if (startIndex >= lineLength) return startIndex

            var low = startIndex + 1
            var high = lineLength
            var best = startIndex

            while (low <= high) {
                val mid = (low + high) ushr 1
                if (fitsInWidth(line, startIndex, mid, targetPixelWidth, letterSpacingPx, measurePaint)) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            return if (best <= startIndex) startIndex + 1 else best
        }

        private fun fitsInWidth(
            line: String,
            startIndex: Int,
            endIndex: Int,
            targetPixelWidth: Float,
            letterSpacingPx: Float,
            measurePaint: Paint
        ): Boolean {
            val charCount = endIndex - startIndex
            val textWidth = measurePaint.measureText(line, startIndex, endIndex)
            val totalWidth = textWidth + charCount * letterSpacingPx
            return totalWidth <= targetPixelWidth
        }

        private fun adjustBreakForHorizontalPunctuation(
            line: String,
            startIndex: Int,
            candidateEndIndex: Int
        ): Int {
            val lineLength = line.length
            var splitIndex = candidateEndIndex.coerceIn(startIndex + 1, lineLength)
            if (splitIndex >= lineLength) return lineLength

            var currentNextChar = line[splitIndex].code
            var isNextStartDeny = isLineStartDenied(currentNextChar)

            if (isNextStartDeny) {
                // 保持旧横屏逻辑：如果下一行会以避头标点开头，就把连续避头标点并入当前行。
                // 这可能让当前行略超宽，但能保留原先的中文排版规则。
                while (isNextStartDeny) {
                    splitIndex++
                    if (splitIndex < lineLength) {
                        currentNextChar = line[splitIndex].code
                        isNextStartDeny = isLineStartDenied(currentNextChar)
                    } else {
                        break
                    }
                }
            } else {
                // 保持旧横屏逻辑：如果当前行末尾是避尾标点，就回退断点。
                while (splitIndex > startIndex + 1) {
                    val lastChar = line[splitIndex - 1].code
                    if (isLineEndDenied(lastChar)) {
                        splitIndex--
                    } else {
                        break
                    }
                }
            }

            return splitIndex.coerceIn(startIndex + 1, lineLength)
        }

        /**
         * 竖屏文本分页。
         * 图片、空行、段首缩进、章节标题、isParagraphEnd 均保持原行为。
         */
        fun pagingTextVertical(
            rawContentList: List<Content>,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit,
            charRatios: FloatArray,
            typeface: Typeface
        ): List<Content> {
            val targetPixelWidth = ValueUtil.dpToPx(width)
            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)
            val measurePaint = createMeasurePaint(fontSizePx, typeface)

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
                        val line = rawLine.trimEnd(*TRIM_CHARS)

                        if (line.isBlank()) {
                            if (resultLines.isNotEmpty()) {
                                val lastContent = resultLines.last()
                                if (lastContent.type != ContentType.TEXT || lastContent.data.isNotEmpty()) {
                                    resultLines.add(Content("", ContentType.TEXT, chapterTitle))
                                }
                            }
                            isStartOfParagraph = true
                        } else {
                            val lineToChunk = if (isStartOfParagraph) {
                                val trimmedLine = line.trimStart(*TRIM_CHARS)
                                isStartOfParagraph = false
                                "　　$trimmedLine"
                            } else {
                                line
                            }

                            chunkLineVerticalExact(
                                line = lineToChunk,
                                targetPixelWidth = targetPixelWidth,
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

        private fun chunkLineVerticalExact(
            line: String,
            targetPixelWidth: Float,
            letterSpacingPx: Float,
            @Suppress("UNUSED_PARAMETER") charRatios: FloatArray,
            measurePaint: Paint,
            chapterTitle: String?,
            output: MutableList<Content>
        ) {
            val lineLength = line.length
            var startIndex = 0

            while (startIndex < lineLength) {
                val chunkStartIndex = startIndex
                var endIndex = findMaxEndIndexByBinarySearch(
                    line = line,
                    startIndex = startIndex,
                    targetPixelWidth = targetPixelWidth,
                    letterSpacingPx = letterSpacingPx,
                    measurePaint = measurePaint
                )

                if (endIndex < lineLength) {
                    endIndex = adjustBreakForVerticalPunctuation(
                        line = line,
                        startIndex = startIndex,
                        candidateEndIndex = endIndex
                    )
                } else {
                    endIndex = lineLength
                }

                startIndex = endIndex
                while (startIndex < lineLength && line[startIndex] == ' ') {
                    startIndex++
                }

                output.add(
                    Content(
                        line.substring(chunkStartIndex, endIndex),
                        ContentType.TEXT,
                        chapterTitle,
                        isParagraphEnd = startIndex >= lineLength
                    )
                )
            }
        }

        private fun adjustBreakForVerticalPunctuation(
            line: String,
            startIndex: Int,
            candidateEndIndex: Int
        ): Int {
            val lineLength = line.length
            var splitIndex = candidateEndIndex.coerceIn(startIndex + 1, lineLength)
            if (splitIndex >= lineLength) return lineLength

            // 保持旧竖屏逻辑：避头或避尾命中时都向前回退，而不是像横屏一样吞入标点。
            while (splitIndex > startIndex + 1) {
                val nextChar = line[splitIndex].code
                val lastChar = line[splitIndex - 1].code

                val isNextStartDeny = isLineStartDenied(nextChar)
                val isCurrentEndDeny = isLineEndDenied(lastChar)

                if (isNextStartDeny || isCurrentEndDeny) {
                    splitIndex--
                } else {
                    break
                }
            }

            return splitIndex.coerceIn(startIndex + 1, lineLength)
        }

        private fun isLineStartDenied(charCode: Int): Boolean {
            return charCode < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[charCode]
        }

        private fun isLineEndDenied(charCode: Int): Boolean {
            return charCode < PUNCTUATION_LINE_END_DENY_SET.size && PUNCTUATION_LINE_END_DENY_SET[charCode]
        }
    }
}
