package org.shirakawatyu.yamibo.novel.util.reader

import android.util.TypedValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.global.GlobalData

/**
 * 单位转换工具
 */
class ValueUtil {
    companion object {

        fun dpToPx(dp: Dp): Float {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp.value, GlobalData.Companion.displayMetrics
            )
        }

        fun spToPx(sp: TextUnit): Float {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp.value, GlobalData.Companion.displayMetrics
            )
        }

        fun pxToDp(pxValue: Float): Dp {
            return (pxValue / GlobalData.Companion.displayMetrics!!.density).dp
        }

        fun spToDp(sp: TextUnit): Dp {
            return pxToDp(spToPx(sp))
        }

        fun pxToSp(pxValue: Float): TextUnit {
            return (pxValue / GlobalData.Companion.displayMetrics!!.density).sp
        }
    }
}