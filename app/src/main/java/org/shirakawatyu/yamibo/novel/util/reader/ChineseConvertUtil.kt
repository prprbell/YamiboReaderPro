package org.shirakawatyu.yamibo.novel.util.reader

import android.content.Context
import android.util.LruCache
import com.zqc.opencc.android.lib.ChineseConverter
import com.zqc.opencc.android.lib.ConversionType

/**
 * 中文简繁转换工具。
 *
 * 简体模式仍然保持原来的语义：先 S2TWP，再 TW2SP。
 * 新增转换结果缓存，避免同一页在“原文 / 繁体 / 简体”之间来回切换时反复跑 OpenCC。
 */
object ChineseConvertUtil {

    private const val MODE_SIMPLIFIED = 1
    private const val MODE_TRADITIONAL = 2

    private data class ConvertKey(
        val mode: Int,
        val length: Int,
        val hash: Int
    )

    private val convertCache = LruCache<ConvertKey, String>(8)

    private fun convert(text: String, mode: Int, context: Context): String {
        if (text.isBlank()) return text

        val key = ConvertKey(
            mode = mode,
            length = text.length,
            hash = text.hashCode()
        )
        convertCache.get(key)?.let { return it }

        val result = try {
            when (mode) {
                MODE_SIMPLIFIED -> {
                    val tempTrad = ChineseConverter.convert(text, ConversionType.S2TWP, context)
                    ChineseConverter.convert(tempTrad, ConversionType.TW2SP, context)
                }

                MODE_TRADITIONAL -> {
                    ChineseConverter.convert(text, ConversionType.S2TWP, context)
                }

                else -> text
            }
        } catch (_: Exception) {
            text
        }

        convertCache.put(key, result)
        return result
    }

    /**
     * 简体转繁体
     */
    fun toTraditional(text: String, context: Context): String {
        return convert(text, MODE_TRADITIONAL, context)
    }

    /**
     * 繁体转简体。
     * 注意：为了保持原逻辑，仍然执行 S2TWP -> TW2SP。
     */
    fun toSimplified(text: String, context: Context): String {
        return convert(text, MODE_SIMPLIFIED, context)
    }

    fun clearCache() {
        convertCache.evictAll()
    }
}
