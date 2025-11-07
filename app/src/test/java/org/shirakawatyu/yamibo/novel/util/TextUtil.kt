package org.shirakawatyu.yamibo.novel.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class TextUtil {
    companion object {
        private val executor: ExecutorService = Executors.newFixedThreadPool(8)
        fun pagingText(
            text: String,
            height: Dp,
            width: Dp,
            fontSize: TextUnit,
            letterSpacing: TextUnit,
            lineHeight: TextUnit
        ): List<String> {
            val textLines = text.split("\n")
//          获取目标像素宽度
            val targetPixelWidth = ValueUtil.dpToPx(width)
            val maxLine = (ValueUtil.dpToPx(height) / ValueUtil.spToPx(lineHeight)).toInt()
            val result = ArrayList<String>()
            val resultLines = ArrayList<String>()
            val futureTasks = ArrayList<Future<List<String>>>()
            for (line in textLines) {
                if (line.trim().isEmpty()) {
                    continue
                }
                futureTasks.add(executor.submit(Callable {
                    chunkLine(line, targetPixelWidth, fontSize, letterSpacing)
                }))
            }
            futureTasks.forEach {
                resultLines.addAll(it.get())
            }
            resultLines.chunked(maxLine).forEach {
                result.add(it.joinToString("\n") + "\n")
            }
            return result
        }

        private fun getLastWordIndex(s: String): Int {
            val punctuation = Regex("\\p{Punct}")
            for (i in s.length - 1 downTo  0) {
                if (!punctuation.matches(s[i].toString())) {
                    return i
                }
            }
            return -1
        }

        private fun chunkLine(
            line: String,
            targetPixelWidth: Float,
            fontSize: TextUnit,
            letterSpacing: TextUnit
        ): List<String> {
            // 像素计算辅助函数
            val spPx = ValueUtil.spToPx(fontSize)
            val lsPx = ValueUtil.spToPx(letterSpacing)
            fun getWidthPx(c: Char): Float {
                // ASCII 可打印字符范围
                val charWidthRatio = if (c.code in 0x0020..0x007e) 0.5f else 1.0f
                return (charWidthRatio * spPx) + lsPx
            }
            var cnt = 0.0f
            val chunks = ArrayList<String>()
            var newLine = ArrayList<Char>()
            for (c in line) {
                val charWidth = getWidthPx(c)
                // 比较像素宽度>目标像素宽度
                if (cnt + charWidth > targetPixelWidth && newLine.isNotEmpty()) {
                    chunks.add(String(newLine.toCharArray()))
                    newLine = ArrayList()
                    cnt = 0.0f
                }
                if (chunks.isNotEmpty() && newLine.size == 0 && c.toString().matches(Regex("\\p{Punct}"))) {
                    val s = chunks[chunks.size - 1]
                    val lastWordIndex = getLastWordIndex(s)
                    if (lastWordIndex != -1) {
                        val wordSeq = s.substring(lastWordIndex)
                        newLine.addAll(wordSeq.toList())
                        wordSeq.forEach {
                            cnt += getWidthPx(it)
                        }
                        chunks[chunks.size - 1] = s.substring(0, s.length - wordSeq.length)
                    }
                }
                cnt += charWidth
                newLine.add(c)
            }
            if (newLine.isNotEmpty()) {
                chunks.add(String(newLine.toCharArray()))
            }
            return chunks
        }
    }
}