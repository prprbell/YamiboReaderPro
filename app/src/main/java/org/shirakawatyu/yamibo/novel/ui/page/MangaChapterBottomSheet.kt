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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy
import org.shirakawatyu.yamibo.novel.bean.MangaSettings

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
    initialAuthor: String,
    chapters: List<MangaChapter>,
    isUpdating: Boolean = false,
    cooldownSeconds: Int = 0,
    strategy: DirectoryStrategy? = null,
    showSearchShortcut: Boolean = false,
    searchShortcutCountdown: Int = 0,
    onUpdateClick: (isForced: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onChapterClick: (MangaChapter) -> Unit,
    onTitleEdit: (String, String) -> Unit
) {
    val context = LocalContext.current
    var ascending by remember { mutableStateOf(MangaSettings.getSettings(context).isAscending) }
    // 顶部标题的展开状态
    var isTitleExpanded by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf(title) }
    var editAuthorText by remember { mutableStateOf(initialAuthor) }

    val sorted = remember(chapters, ascending) {
        if (ascending) chapters else chapters.reversed()
    }
    val listState = rememberLazyListState()
    val offsetY = remember { Animatable(2000f) }
    val scrimAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    var dragJob by remember { mutableStateOf<Job?>(null) }
    val isSearchMode = strategy != DirectoryStrategy.TAG || showSearchShortcut
    val canUpdate = !isUpdating && cooldownSeconds <= 0

    // buttonText 里用上倒计时
    val buttonText = when {
        isUpdating -> "更新中"
        cooldownSeconds > 0 -> "${cooldownSeconds}s"
        isSearchMode -> if (searchShortcutCountdown > 0) "全局搜索 ${searchShortcutCountdown}s" else "全局搜索"
        else -> "更新"
    }
    val buttonBgColor = when {
        !canUpdate -> Color(0xFF2A2D35) // 禁用色
        isSearchMode -> Color(0xFF6366F1) // 全局搜索：蓝色/紫色
        else -> Accent // 普通更新：原有橙色
    }
    LaunchedEffect(Unit) {
        launch {
            offsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 350)
            )
        }
        launch {
            scrimAlpha.animateTo(
                targetValue = 0.6f,
                animationSpec = tween(durationMillis = 350)
            )
        }
    }
    if (showEditDialog) {
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                containerColor = BgItem,
                titleContentColor = TextPri,
                textContentColor = TextPri,
                title = {
                    Column {
                        Text("校正漫画信息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "确保书名无任何单章信息",
                            fontSize = 12.sp,
                            color = TextSec,
                            fontWeight = FontWeight.Normal
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editAuthorText,
                            onValueChange = { editAuthorText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("额外关键词", color = TextSec) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Accent,
                                unfocusedTextColor = Accent,
                                cursorColor = Accent,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = TextSec
                            )
                        )
                        OutlinedTextField(
                            value = editTitleText,
                            onValueChange = { editTitleText = it },
                            singleLine = false, minLines = 1, maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("漫画名称", color = TextSec) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Accent,
                                unfocusedTextColor = Accent,
                                cursorColor = Accent,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = TextSec
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editTitleText.isNotBlank()) {
                            onTitleEdit(editTitleText.trim(), editAuthorText.trim())
                        }
                        showEditDialog = false
                    }) {
                        Text("保存并应用", color = Accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditDialog = false }) {
                        Text(
                            "取消",
                            color = TextSec
                        )
                    }
                }
            )
        }
    }
    LaunchedEffect(sorted) {
        val index = sorted.indexOfFirst { it.isCurrent }
        if (index != -1) {
            listState.scrollToItem(
                index = (index - 2).coerceAtLeast(0)
            )
        }
    }
    fun dismiss() {
        scope.launch {
            val slideOut = launch {
                offsetY.animateTo(
                    targetValue = 2000f,
                    animationSpec = tween(durationMillis = 400)
                )
            }
            val fadeOut = launch {
                scrimAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 380)
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
                .background(BgSheet.copy(alpha = 0.95f))
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
                            .padding(start = 20.dp, end = 20.dp, bottom = 18.dp)
                            .animateContentSize(), // 添加平滑展开动画
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = TextPri,
                            fontSize = 16.sp,
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        ascending = !ascending
                                        MangaSettings.saveIsAscending(context, ascending)
                                    }
                                    .background(Color(0xFF1C2028))
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (ascending) "正序" else "倒序",
                                    color = TextSec,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // 修改名称按钮
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        editTitleText = title
                                        showEditDialog = true
                                    }
                                    .background(Color(0xFF1C2028))
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(text = "校正漫画信息", color = Accent, fontSize = 12.sp)
                            }
                        }

                        // 右侧区域：最新话数 + 更新按钮
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val latestChapter = chapters.filter {
                                // 屏蔽时间线算法生成的 3 位小数隐藏章节
                                it.index.toString().substringAfter(".", "").length < 3 &&
                                        // 屏蔽所有番外/特典，不让它们抢占“最新进度”
                                        !it.title.contains(
                                            Regex(
                                                "番外|特典|附录|SP",
                                                RegexOption.IGNORE_CASE
                                            )
                                        )
                            }.maxByOrNull { it.index }

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
                                    .clickable(enabled = canUpdate) {
                                        onUpdateClick(isSearchMode)
                                    }
                                    .background(buttonBgColor)
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
                                        text = buttonText,
                                        color = when {
                                            !canUpdate -> TextSec
                                            isSearchMode -> Color.White   // 蓝紫底色配白字
                                            else -> Color(0xFF111318)     // 橙色底色配深字
                                        },
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
    // 格式化左侧的序号展示
    val displayIndex = when {
        chapter.title.contains(Regex("番外|特典|附录|SP", RegexOption.IGNORE_CASE)) -> "SP"
        chapter.index == 999f -> "终"
        // 1. 隐藏时间线算法生成的 3 位小数 (如 32.001) -> 显示为 Ex
        chapter.index.toString().substringAfter(".", "").length >= 3 -> "Ex"

        // 2. 隐藏 0f 以及误判的 0.1/0.2：
        chapter.index < 1f && !chapter.title.contains(Regex("0|零|〇")) -> "Ex"

        chapter.index % 1f == 0f -> chapter.index.toInt().toString() // 4.0 -> "4"
        else -> chapter.index.toString() // 29.5 -> "29.5"
    }

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