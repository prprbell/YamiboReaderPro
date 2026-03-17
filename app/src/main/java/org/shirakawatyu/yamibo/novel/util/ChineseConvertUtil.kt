package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import com.zqc.opencc.android.lib.ChineseConverter
import com.zqc.opencc.android.lib.ConversionType

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