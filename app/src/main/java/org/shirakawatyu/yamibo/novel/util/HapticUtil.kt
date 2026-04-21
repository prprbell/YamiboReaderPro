package org.shirakawatyu.yamibo.novel.util

import android.view.HapticFeedbackConstants
import android.view.View

object HapticUtil {
    /**
     * 轻微震动
     */
    fun performTick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * 稍重震动
     */
    fun performLongPress(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}