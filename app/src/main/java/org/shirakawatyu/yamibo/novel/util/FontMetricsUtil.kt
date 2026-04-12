package org.shirakawatyu.yamibo.novel.util

import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

object FontMetricsUtil {
    private val ratioCache = ConcurrentHashMap<String, FloatArray>()

    fun getAsciiWidthRatios(typeface: Typeface, fontSizePx: Float): FloatArray {
        val cacheKey = "${typeface.hashCode()}_${fontSizePx}"

        return ratioCache.getOrPut(cacheKey) {
            generateCharWidthRatios(typeface, fontSizePx)
        }
    }

    private fun generateCharWidthRatios(typeface: Typeface, fontSizePx: Float): FloatArray {
        val ratios = FloatArray(65536) { -1f }

        if (fontSizePx <= 0f) return ratios

        val paint = Paint().apply {
            this.isAntiAlias = true
            this.typeface = typeface
            this.textSize = fontSizePx
            this.fontFeatureSettings = "\"palt\""
        }

        for (i in 0..127) {
            val charStr = i.toChar().toString()
            ratios[i] = paint.measureText(charStr) / fontSizePx
            if (i == 32 && ratios[i] < 0.2f) {
                ratios[i] = 0.25f
            }
        }

        return ratios
    }

}