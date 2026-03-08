package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    // 顶部标题的展开状态
    var isTitleExpanded by remember { mutableStateOf(false) }

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
            delay(200)
            listState.animateScrollToItem(
                index = (index - 2).coerceAtLeast(0)
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

                    // 第一行：标题 (支持点击展开)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                            .animateContentSize(), // 添加平滑展开动画
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = TextPri,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (isTitleExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null // 去除点击波纹，因为这里只是纯净的文字展开
                                ) { isTitleExpanded = !isTitleExpanded }
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
                Spacer(Modifier.height(2.dp))
            }
            // 章节列表
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = sorted,
                    key = { _, chapter -> chapter.url }
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
    // 【核心修复】格式化左侧的序号展示
    val displayIndex = when {
        chapter.index >= 1000f -> "SP"   // 番外/附录统一显示为 SP
        chapter.index == 999f -> "终"    // 兜底最终话

        // 1. 隐藏时间线算法生成的 3 位小数 (如 32.001) -> 显示为 Ex
        chapter.index.toString().substringAfter(".", "").length >= 3 -> "Ex"

        // 2. 隐藏 0f 以及误判的 0.1/0.2：
        // 只要底层计算出的话数小于 1 (比如 0.0, 0.1, 0.2)，且原标题里根本没有显式地写 "0"、"零" 或 "〇"
        // 统统视为无话数的短篇或被“上/下”关键字误伤的帖子，显示为 Ex
        chapter.index < 1f && !chapter.title.contains(Regex("0|零|〇")) -> "Ex"

        chapter.index % 1f == 0f -> chapter.index.toInt().toString() // 4.0 -> "4"
        else -> chapter.index.toString() // 29.5 -> "29.5"
    }

    // 【核心修复】：使用 chapter.url 作为 remember 的 key。
    // 这样当 LazyColumn 复用这个组件给另一个章节时，这两个状态会“瞬间、同步”地重置为 false，彻底消灭滑动闪烁。
    var isExpanded by remember(chapter.url) { mutableStateOf(false) }
    var isTruncated by remember(chapter.url) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable {
                // 当前话防重复跳转
                if (!chapter.isCurrent) {
                    onClick()
                }
            }
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .animateContentSize(),
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
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
                // 【核心修复】：只有在“未展开”的状态下才去判定是否截断
                // 只要发现视觉溢出，就立刻标记为已截断（无论如何滑动都不会丢失该判定）
                if (!isExpanded && textLayoutResult.hasVisualOverflow) {
                    isTruncated = true
                }
            },
            modifier = Modifier.weight(1f)
        )

        // 如果名字太长被截断了，就显示操作按钮
        if (isTruncated) {
            Text(
                text = if (isExpanded) "收起" else "展开",
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { isExpanded = !isExpanded }
                    .padding(vertical = 0.25.dp, horizontal = 2.dp)
            )
        }

        when {
            chapter.isNew -> Box(
                modifier = Modifier
                    .padding(start = 6.dp)
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
                    .padding(start = 6.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Accent)
            )

            else -> Spacer(Modifier.width(6.dp))
        }
    }
}