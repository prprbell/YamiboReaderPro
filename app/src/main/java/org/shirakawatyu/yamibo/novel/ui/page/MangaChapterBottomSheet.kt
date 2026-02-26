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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val index: Int,
    val title: String,
    val isRead: Boolean = false,
    val isNew: Boolean = false,
    val isCurrent: Boolean = false
)

fun mockChapters(): List<MangaChapter> = listOf(
    MangaChapter(56, "终章·她的名字", isNew = true),
    MangaChapter(55, "最后的约定"),
    MangaChapter(54, "彼岸花开"),
    MangaChapter(53, "雨夜的真相"),
    MangaChapter(52, "破碎的镜子", isCurrent = true),
    MangaChapter(51, "告白与背叛", isRead = true),
    MangaChapter(50, "相遇在屋顶", isRead = true),
    MangaChapter(49, "谎言的代价", isRead = true),
    MangaChapter(48, "深夜的电话", isRead = true),
    MangaChapter(47, "转学生", isRead = true),
    MangaChapter(46, "春日序章", isRead = true),
    MangaChapter(45, "心跳的距离", isRead = true),
    MangaChapter(44, "放学后", isRead = true),
    MangaChapter(43, "秘密", isRead = true),
    MangaChapter(42, "初遇", isRead = true),
)

@Composable
fun MangaChapterPanel(
    modifier: Modifier = Modifier,
    title: String = "不可能的初恋",
    chapters: List<MangaChapter> = mockChapters(),
    onDismiss: () -> Unit,
    onChapterClick: (MangaChapter) -> Unit
) {
    var ascending by remember { mutableStateOf(true) }
    val sorted = remember(chapters, ascending) {
        if (ascending) chapters else chapters.reversed()
    }

    val offsetY = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0.6f) }
    val scope = rememberCoroutineScope()

    var dragJob by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        scope.launch {
            // 面板下滑 + 遮罩淡出并行
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

    // 最外层 Box 同时承载遮罩 + 面板
    Box(modifier = modifier.fillMaxSize()) {

        // 遮罩，alpha 与面板动画同步；点击遮罩也可关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha.value))
                .clickable { dismiss() }
        )

        // 面板本体，底部对齐
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .offset(y = offsetY.value.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(BgSheet)
        ) {
            // 拖拽把手区域（只有这里响应拖拽，不影响列表滚动）
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
                    // 把手指示条
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

                    // 第二行：正序/倒序（左） + 最新话数（右）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 20.dp, bottom = 12.dp),
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
                        val latestChapter = chapters.maxByOrNull { it.index }
                        Text(
                            text = if (latestChapter != null) "最新：第 ${latestChapter.index} 话" else "",
                            color = TextSec,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            HorizontalDivider(color = Divider, thickness = 1.dp)

            // 章节列表
            LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(sorted) { _, chapter ->
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
            text = chapter.index.toString(),
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