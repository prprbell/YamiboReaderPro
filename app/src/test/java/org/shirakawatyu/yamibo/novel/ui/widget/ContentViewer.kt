package org.shirakawatyu.yamibo.novel.ui.widget

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData

@Composable
fun ContentViewer(
    data: Content,
    padding: Dp = 16.dp,
    lineHeight: TextUnit = 39.sp,
    fontSize: TextUnit = 18.sp,
    letterSpacing: TextUnit = 2.sp,
    currentPage: Int,
    pageCount: Int
) {
    // 定义上下边距
    val verticalPadding = 16.dp

    Column (
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = padding)
    ){
        if (data.type == ContentType.IMG) {
            SubcomposeAsyncImage(
                modifier = Modifier.weight(1f),
                model = ImageRequest.Builder(LocalContext.current)
                    .data(data.data)
                    .crossfade(true)
                    .addHeader("Cookie", GlobalData.cookie)
                    .addHeader("User-Agent", RequestConfig.UA)
                    .build(),
                onError = {
                    it.result.throwable.printStackTrace()
                    Log.e("SubcomposeAsyncImage", data.data) },
                error = {it.result},
                contentDescription = "",
                loading = { CircularProgressIndicator() })
        } else if (data.type == ContentType.TEXT) {
            Text(
                modifier = Modifier
                    .padding(vertical = verticalPadding)
                    .weight(1f)
                    .fillMaxWidth(),
                lineHeight = lineHeight,
                fontSize = fontSize,
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                text = data.data
            )
        }
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            textAlign = TextAlign.End,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
            fontSize = 12.sp,
            color = Color.DarkGray,
            text = "${currentPage}/${pageCount}"
        )
    }
}