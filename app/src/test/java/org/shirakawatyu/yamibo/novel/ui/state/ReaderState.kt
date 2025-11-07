package org.shirakawatyu.yamibo.novel.ui.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.bean.Content

data class ReaderState (
    val htmlList: List<Content> = listOf(),
    val currentView: Int = 1,
    val initPage: Int = 0,
    val lineHeight: TextUnit = 39.sp,
    val padding: Dp = 16.dp,
    val fontSize: TextUnit = 18.sp,
    val letterSpacing:TextUnit = 2.sp,
    var scale: Float = 1f,
    var offset: Offset = Offset(0f, 0f)
)