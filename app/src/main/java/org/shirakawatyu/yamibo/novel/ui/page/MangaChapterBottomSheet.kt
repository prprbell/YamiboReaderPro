package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BgSheet = Color(0xFF111318)
private val BgItem = Color(0xFF1C2028)
private val BgRead = Color(0xFF161A20)
private val Accent = Color(0xFFFF5F3A)
private val TextPri = Color(0xFFE0DCD6)
private val TextSec = Color(0xFF6B7280)
private val TextRead = Color(0xFF3D4454)
private val Divider = Color(0xFF222630)

data class MangaChapter(
    val index: Float,
    val title: String,
    val url: String,
    val isRead: Boolean = false,
    val isNew: Boolean = false,
    val isCurrent: Boolean = false
)

@Composable
fun MangaChapterPanel(
    modifier: Modifier = Modifier,
    title: String,
    chapters: List<MangaChapter>,
    isUpdating: Boolean = false,
    cooldownSeconds: Int = 0,
    onUpdateClick: () -> Unit = {},
    onDismiss: () -> Unit,
    onChapterClick: (MangaChapter) -> Unit
) {
    var ascending by remember { mutableStateOf(true) }
    val sorted = remember(chapters, ascending) {
        if (ascending) chapters else chapters.reversed()
    }
    val listState = rememberLazyListState()
    val offsetY = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0.6f) }
    val scope = rememberCoroutineScope()

    var dragJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(sorted) {
        val index = sorted.indexOfFirst { it.isCurrent }
        if (index != -1) {
            // 延迟 200ms 等待 BottomSheet 弹出动画稳定，滚动会更顺滑
            delay(200)
            listState.animateScrollToItem(
                index = (index - 2).coerceAtLeast(0) // 让当前项显示在靠近中间的位置
            )
        }
    }
    fun dismiss() {
        scope.launch {
            val slideOut = launch {
                offsetY.animateTo(
                    targetValue = 2000f,
                    animationSpec = tween(durationMillis = 480)
                )
            }
            val fadeOut = launch {
                scrimAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 464)
                )
            }
            slideOut.join()
            fadeOut.join()
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha.value))
                .clickable { dismiss() }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .offset(y = offsetY.value.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(BgSheet)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (offsetY.value > 120f) {
                                        dismiss()
                                    } else {
                                        offsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                dragJob?.cancel()
                                dragJob = scope.launch {
                                    val next = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                    offsetY.snapTo(next)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF404655))
                    )
                    Spacer(Modifier.height(10.dp))

                    // 第一行：标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = TextPri,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 第二行：正序/倒序（左） + 最新话数与更新按钮（右）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { ascending = !ascending }
                                .background(Color(0xFF1C2028))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (ascending) "正序" else "倒序",
                                color = TextSec,
                                fontSize = 12.sp
                            )
                        }

                        // 右侧区域：最新话数 + 更新按钮
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val latestChapter =
                                chapters.filter { it.index < 1000f }.maxByOrNull { it.index }

                            val latestText = latestChapter?.let {
                                if (it.index % 1f == 0f) it.index.toInt()
                                    .toString() else it.index.toString()
                            } ?: ""

                            Text(
                                text = if (latestChapter != null) "最新: 第${latestText}话" else "",
                                color = TextSec,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.width(10.dp))

                            val canUpdate = !isUpdating && cooldownSeconds <= 0
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = canUpdate) { onUpdateClick() }
                                    .background(if (canUpdate) Accent else Color(0xFF2A2D35))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // 根据更新状态切换 UI
                                if (isUpdating) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = TextSec,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "更新中",
                                            color = TextSec,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (cooldownSeconds > 0) "${cooldownSeconds}s" else "更新",
                                        color = if (canUpdate) Color(0xFF111318) else TextSec,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Divider, thickness = 1.dp)

            if (isUpdating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Accent,
                    trackColor = Color.Transparent
                )
            } else {
                // 占位，防止动画出现/消失时列表发生上下抖动跳跃
                Spacer(Modifier.height(2.dp))
            }
            // 章节列表
            LazyColumn(
                state = listState, // 绑定状态
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = sorted,
                    key = { _, chapter -> chapter.url } // 建议增加 key 以优化滚动性能
                ) { _, chapter ->
                    ChapterRow(chapter = chapter, onClick = { onChapterClick(chapter) })
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: MangaChapter, onClick: () -> Unit) {
    val bg = when {
        chapter.isCurrent -> Color(0xFF1E2535)
        chapter.isRead -> BgRead
        else -> BgItem
    }
    val textColor = if (chapter.isRead && !chapter.isCurrent) TextRead else TextPri
    val numColor = when {
        chapter.isCurrent -> Accent
        chapter.isRead -> TextRead
        else -> TextSec
    }
    // 【新增】格式化左侧的序号展示
    val displayIndex = when {
        chapter.index >= 1000f -> "SP" // 番外/附录统一显示为 SP (Special)
        chapter.index % 1f == 0f -> chapter.index.toInt().toString() // 4.0 -> "4"
        else -> chapter.index.toString() // 4.1 -> "4.1"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayIndex,
            color = numColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = chapter.title,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            chapter.isNew -> Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "NEW",
                    color = Color(0xFF111318),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            chapter.isCurrent -> Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Accent)
            )

            else -> Spacer(Modifier.width(6.dp))
        }
    }
}