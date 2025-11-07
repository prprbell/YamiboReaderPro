package org.shirakawatyu.yamibo.novel.ui.widget

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    lineHeight: TextUnit = 43.sp,
    fontSize: TextUnit = 24.sp,
    letterSpacing: TextUnit = 2.sp,
    currentPage: Int,
    pageCount: Int,
    nightMode: Boolean
) {
    // 定义上下边距
    val topPadding = 16.dp
    val cookie by GlobalData.cookieFlow.collectAsState(initial = "")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = padding)
    ) {
        if (data.type == ContentType.IMG) {
            val imageRequestBuilder = ImageRequest.Builder(LocalContext.current)
                .data(data.data)
                .crossfade(true)
                .addHeader("User-Agent", RequestConfig.UA)
                .bitmapConfig(Bitmap.Config.RGB_565)

            if (cookie.isNotBlank()) {
                imageRequestBuilder.addHeader("Cookie", cookie)
            }

            SubcomposeAsyncImage(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                model = imageRequestBuilder.build(),
                onError = {
                    it.result.throwable.printStackTrace()
                    Log.e("SubcomposeAsyncImage", data.data)
                },
                error = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            "Image failed to load",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "图片加载失败",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                contentDescription = "Image Content",
                loading = { CircularProgressIndicator() })
        } else if (data.type == ContentType.TEXT) {
            JustifiedText(
                modifier = Modifier
                    .padding(top = topPadding)
                    .weight(1f)
                    .fillMaxWidth(),
                text = data.data,
                lineHeight = lineHeight,
                fontSize = fontSize,
                letterSpacing = letterSpacing,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 1.dp, end = 1.dp, top = 1.dp, bottom = 5.dp)
                .height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween, // 左右对齐
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：章节标题
            Text(
                modifier = Modifier.weight(0.98f),
                text = data.chapterTitle ?: "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 右侧：页码
            Text(
                modifier = Modifier
                    .padding(end = 20.dp),
                text = "${currentPage}/${pageCount}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End // 右对齐
            )
        }
    }
}