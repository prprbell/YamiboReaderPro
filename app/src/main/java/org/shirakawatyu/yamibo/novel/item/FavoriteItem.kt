package org.shirakawatyu.yamibo.novel.item

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM

/**
 * 收藏项组件，用于展示收藏的小说条目信息。
 *
 * @param title 小说标题
 * @param lastView 上次阅读的网页页码
 * @param lastPage 上次阅读的阅读器页码
 * @param lastChapter 上次阅读的章节名称，可能为空
 * @param onClick 点击该项时触发的回调函数
 * @param modifier 组件修饰符，默认为Modifier
 * @param isDragging 是否正在拖拽状态，影响动画效果，默认为false
 * @param dragHandle 拖拽手柄的可组合项
 */
@Composable
fun FavoriteItem(
    title: String,
    lastView: Int,
    lastPage: Int,
    lastChapter: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    dragHandle: @Composable (() -> Unit) = {},
    isManageMode: Boolean = false,
    isSelected: Boolean = false,
    isHidden: Boolean = false,
    type: Int = 0,
    cacheInfo: FavoriteVM.CacheInfo? = null,
    isGlobalCollapsed: Boolean = false
) {
    var isExpandedLocally by remember(isGlobalCollapsed) { mutableStateOf(false) }

    val isEffectivelyCollapsed = isGlobalCollapsed && !isExpandedLocally

    val displayTitle = remember(title) {
        buildAnnotatedString {
            append(title)
            val prefixRegex = Regex("^(?:[【\\[].*?[】\\]]|[\\s\\u00A0\\u3000])+")
            val prefixMatch = prefixRegex.find(title)

            if (prefixMatch != null) {
                val tagRegex = Regex("[【\\[].*?[】\\]]")
                val tags = tagRegex.findAll(prefixMatch.value)
                for (tag in tags) {
                    addStyle(
                        style = SpanStyle(
                            fontSize = 14.sp,
                            color = Color.Black.copy(alpha = 0.7f)
                        ),
                        start = tag.range.first,
                        end = tag.range.last + 1
                    )
                }
            }
        }
    }

    // 格式化文件大小的辅助函数
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // 拖拽动画：根据是否处于拖拽状态动态调整卡片的阴影、缩放和颜色
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 1.dp,
        label = "elevation_animation"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1.0f,
        label = "scale_animation"
    )
    val color by animateColorAsState(
        targetValue = when {
            isDragging -> YamiboColors.onSurface // 拖拽时
            isManageMode && isSelected -> YamiboColors.secondary // 管理模式 + 选中
            isManageMode && isHidden -> Color.LightGray // 管理模式+已隐藏(未选中)
            else -> YamiboColors.tertiary // 默认
        },
        label = "color_animation"
    )

    val typeColor = when (type) {
        1 -> Color(0xFFE8F5E9) to Color(0xFF4CAF50) // 绿：明亮的鲜绿色
        2 -> Color(0xFFE3F2FD) to Color(0xFF2196F3) // 蓝：明亮的亮蓝色
        3 -> Color(0xFFFFF3E0) to Color(0xFFFF9800) // 橙：明亮的活力橙
        else -> Color(0xFFF5F5F5) to Color(0xFF9E9E9E) // 灰：明亮的中性灰
    }

    val middleColor = lerp(typeColor.first, typeColor.second, 0.75f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(5.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = middleColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(3.dp.toPx(), size.height)
                    )
                }
                .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(250, easing = FastOutSlowInEasing)),
                    fontSize = 16.sp,
                    color = Color.Black,
                    maxLines = if (isEffectivelyCollapsed) 2 else 3,
                    text = displayTitle,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(
                        lineBreak = LineBreak.Simple
                    )
                )

                AnimatedVisibility(
                    visible = !isEffectivelyCollapsed,
                    enter = expandVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                            fadeIn(animationSpec = tween(250, delayMillis = 40, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                            fadeOut(animationSpec = tween(150)) // 收起时淡出要稍快一点，防止文字残留交叠
                ) {
                    Column(
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        // 显示最近阅读章节名
                        if (lastChapter != null && lastChapter.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(0.dp, 2.dp),
                                color = Color.Black.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                text = lastChapter,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (type == 1) {
                            Text(
                                color = Color.Black.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                text = "上次读到第${lastPage + 1}页, 对应网页第${lastView}页"
                            )
                        }
                        if (cacheInfo != null && cacheInfo.totalPages > 0) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cache),
                                    contentDescription = "已缓存",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    text = "已缓存 ${cacheInfo.totalPages} 页 (${
                                        formatFileSize(
                                            cacheInfo.totalSize
                                        )
                                    })"
                                )
                            }
                        }
                    }
                }

                if (isManageMode && isHidden) {
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        text = "[已隐藏]"
                    )
                }
            }

            // 右侧：折叠箭头 / 拖拽手柄
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isGlobalCollapsed && !isManageMode) {
                    IconButton(
                        onClick = { isExpandedLocally = !isExpandedLocally },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpandedLocally) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpandedLocally) "收起" else "展开",
                            tint = YamiboColors.primary
                        )
                    }
                } else {
                    dragHandle()
                }
            }
        }
    }
}