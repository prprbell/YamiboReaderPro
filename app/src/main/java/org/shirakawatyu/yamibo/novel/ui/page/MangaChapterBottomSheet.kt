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
private val SpecialChapterRegex = Regex("番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画", RegexOption.IGNORE_CASE)
private val ChapterIndexFormat = java.text.DecimalFormat("0.###")
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
    var editKeyword1 by remember { mutableStateOf(initialAuthor) }
    var editKeyword2 by remember { mutableStateOf("") }
    var showSecondKeyword by remember { mutableStateOf(false) }

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
        !canUpdate -> Color(0xFF2A2D35)
        isSearchMode -> Color(0xFF6366F1)
        else -> Accent
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editKeyword1,
                            onValueChange = { editKeyword1 = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text("关键词 1", color = TextSec) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Accent,
                                unfocusedTextColor = Accent,
                                cursorColor = Accent,
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = TextSec
                            )
                        )

                        if (showSecondKeyword) {
                            OutlinedTextField(
                                value = editKeyword2,
                                onValueChange = { editKeyword2 = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                label = { Text("关键词 2", color = TextSec) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Accent,
                                    unfocusedTextColor = Accent,
                                    cursorColor = Accent,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = TextSec
                                )
                            )
                        } else {
                            // 加号按钮：点击后显示第二个输入框
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp) // 稍微下移以对齐输入框主体
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1C2028))
                                    .clickable { showSecondKeyword = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            }
                        }
                    }
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
                        val combinedKeywords = listOf(editKeyword1.trim(), editKeyword2.trim())
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        onTitleEdit(editTitleText.trim(), combinedKeywords)
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

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val latestChapter = chapters.filter {
                                it.index.toString().substringAfter(".", "").length < 3 &&
                                        !it.title.contains(
                                            Regex(
                                                "番外|特典|附录|SP|卷后附|卷彩页",
                                                RegexOption.IGNORE_CASE
                                            )
                                        )
                            }.maxByOrNull { it.index }

                            val latestText = latestChapter?.let { chap ->
                                when {
                                    chap.title.contains(Regex("番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画", RegexOption.IGNORE_CASE)) -> "SP"
                                    chap.index == 999f -> "终"
                                    chap.index < 1f && !chap.title.contains(Regex("[0零〇]")) -> "Ex"
                                    else -> {
                                        val safeStr = java.text.DecimalFormat("0.###").format(chap.index)
                                        if (safeStr.contains(".")) {
                                            val parts = safeStr.split(".")
                                            if (parts[1].length >= 3) "Ex" else "${parts[0]}-${parts[1].trimStart('0')}"
                                        } else safeStr
                                    }
                                }
                            } ?: ""

                            Text(
                                text = if (latestChapter != null) "最新: 第${latestText}话" else "",
                                color = TextSec,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.width(5.dp))

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
                                            isSearchMode -> Color.White
                                            else -> Color(0xFF111318)
                                        },
                                        fontSize = 11.sp,
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
        chapter.title.contains(SpecialChapterRegex) -> "SP"
        chapter.index == 999f -> "终"
        chapter.index < 1f && !chapter.title.contains(Regex("[0零〇]")) -> "Ex"
        else -> {
            val safeStr = ChapterIndexFormat.format(chapter.index)

            if (safeStr.contains(".")) {
                val parts = safeStr.split(".")
                val integerPart = parts[0]
                val fractionalPart = parts[1].trimStart('0')

                if (parts[1].length >= 3) {
                    "Ex"
                } else {
                    "$integerPart-$fractionalPart"
                }
            } else {
                safeStr
            }
        }
    }

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