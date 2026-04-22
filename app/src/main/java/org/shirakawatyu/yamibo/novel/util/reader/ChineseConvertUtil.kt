package org.shirakawatyu.yamibo.novel.util.reader

import android.content.Context
import com.zqc.opencc.android.lib.ChineseConverter
import com.zqc.opencc.android.lib.ConversionType

/**
 * 中文简繁转换工具
 * 提供简体转繁体、繁体转简体的功能
 */
object ChineseConvertUtil {

    /**
     * 简体转繁体
     */
    fun toTraditional(text: String, context: Context): String {
        return try {
            ChineseConverter.convert(text, ConversionType.S2TWP, context)
        } catch (e: Exception) {
            text
        }
    }

    /**
     * 繁体转简体
     */
    fun toSimplified(text: String, context: Context): String {
        return try {
            val tempTrad = ChineseConverter.convert(text, ConversionType.S2TWP, context)

            ChineseConverter.convert(tempTrad, ConversionType.TW2SP, context)
        } catch (e: Exception) {
            text
        }
    }
}