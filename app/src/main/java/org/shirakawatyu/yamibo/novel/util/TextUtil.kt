package org.shirakawatyu.yamibo.novel.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType

/**
 * 文本分页工具
 */
class TextUtil {
    companion object {
        private val PUNCTUATION_LINE_START_DENY_SET = BooleanArray(0x10000).apply {
            "，。,、.!？?）」)]}”\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        private val PUNCTUATION_LINE_END_DENY_SET = BooleanArray(0x10000).apply {
            "（(「[{“\"'".forEach { char ->
                if (char.code < size) this[char.code] = true
            }
        }

        private val ASCII_CHAR_WIDTH_RATIOS = FloatArray(128).apply {
            for (i in 0..127) {
                val c = i.toChar()
                this[i] = when (c) {
                    'M', 'W', 'm', '@', '%' -> 0.85f
                    'O', 'Q', 'G', 'C', 'D', 'w', '&', '~' -> 0.75f
                    in 'A'..'Z', '+', '=', '<', '>' -> 0.65f
                    'i', 'j', 'l', 'f', 't', 'r', 'I' -> 0.35f
                    'c', 'k', 's', 'z' -> 0.5f
                    in 'a'..'z', in '2'..'9', '0' -> 0.6f
                    '1' -> 0.45f
                    '.', ',', ':', ';', '\'', '"', '!', '|', '`', '-',
                    '(', ')', '[', ']', '{', '}' -> 0.3f
                    ' ' -> 0.25f
                    else -> 0.55f
                }
            }
        }

        fun pagingText(
            text: String,
            height: Dp,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit,
            lineHeight: TextUnit
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
            val fullWidthPx = fontSizePx + letterSpacingPx

            return performPaging(
                text = text,
                targetPixelWidth = targetPixelWidth,
                maxLine = maxLine,
                fullWidthPx = fullWidthPx
            )
        }

        private fun calculateMaxLines(
            totalHeightPx: Float,
            lineHeightPx: Float,
            safeAreaRatio: Float = 1.0f
        ): Int {
            // 底部安全距离
            val bottomSafePaddingPx = lineHeightPx * 0.25f
            val safeHeight = (totalHeightPx * safeAreaRatio) - bottomSafePaddingPx
            val calculatedLines = (safeHeight / lineHeightPx).toInt()
            return calculatedLines.coerceAtLeast(1)
        }

        private fun performPaging(
            text: String,
            targetPixelWidth: Float,
            maxLine: Int,
            fullWidthPx: Float
        ): List<String> {
            val avgCharsPerLine = (targetPixelWidth / fullWidthPx).toInt().coerceAtLeast(1)
            val estimatedTotalLines = (text.length / avgCharsPerLine).coerceAtLeast(1)
            val resultLines = ArrayList<String>(estimatedTotalLines)
            var isStartOfParagraph = true

            text.lineSequence().forEach { line ->
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
                        fullWidthPx = fullWidthPx,
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
            fullWidthPx: Float,
            output: MutableList<String>
        ) {
            val lineLength = line.length
            var startIndex = 0
            var i = 0
            var currentWidth = 0.0f

            while (i < lineLength) {
                val c = line[i]
                val charWidth = if (c.code > 127) fullWidthPx else ASCII_CHAR_WIDTH_RATIOS[c.code] * fullWidthPx

                if (currentWidth + charWidth > targetPixelWidth && i > startIndex) {
                    val lastChar = line[i - 1]

                    // 避头
                    if (c.code < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[c.code]) {
                        output.add(line.substring(startIndex, i + 1))
                        startIndex = i + 1
                        i = startIndex
                        currentWidth = 0.0f
                        continue
                    }

                    // 避尾
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

        fun pagingTextVertical(
            rawContentList: List<Content>,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit
        ): List<Content> {

            val targetPixelWidth = ValueUtil.dpToPx(width)
            val fontSizePx = ValueUtil.spToPx(fontSize)
            val letterSpacingPx = ValueUtil.spToPx(letterSpacing)
            val fullWidthPx = fontSizePx + letterSpacingPx

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

                    text.lineSequence().forEach { line ->
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
                                fullWidthPx = fullWidthPx,
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
            fullWidthPx: Float,
            chapterTitle: String?,
            output: MutableList<Content>
        ) {
            val lineLength = line.length
            var startIndex = 0
            var i = 0
            var currentWidth = 0.0f

            while (i < lineLength) {
                val c = line[i]
                val charWidth = if (c.code > 127) fullWidthPx else ASCII_CHAR_WIDTH_RATIOS[c.code] * fullWidthPx

                if (currentWidth + charWidth > targetPixelWidth && i > startIndex) {
                    val lastChar = line[i - 1]

                    if (c.code < PUNCTUATION_LINE_START_DENY_SET.size && PUNCTUATION_LINE_START_DENY_SET[c.code]) {
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