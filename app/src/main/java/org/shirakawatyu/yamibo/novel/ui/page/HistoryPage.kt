package org.shirakawatyu.yamibo.novel.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.bean.HistoryEntry
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.util.history.HistoryUtil
import java.net.URLEncoder
import java.util.Calendar
import kotlin.math.roundToInt

// 自定义日历图标，用于日期筛选器
private val CalendarIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Calendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 4f)
            lineTo(18f, 4f)
            lineTo(18f, 2f)
            lineTo(16f, 2f)
            lineTo(16f, 4f)
            lineTo(8f, 4f)
            lineTo(8f, 2f)
            lineTo(6f, 2f)
            lineTo(6f, 4f)
            lineTo(5f, 4f)
            curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f)
            lineTo(3f, 20f)
            curveTo(3f, 21.1f, 3.89f, 22f, 5f, 22f)
            lineTo(19f, 22f)
            curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
            lineTo(21f, 6f)
            curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f)
            close()
            moveTo(19f, 20f)
            lineTo(5f, 20f)
            lineTo(5f, 10f)
            lineTo(19f, 10f)
            lineTo(19f, 20f)
            close()
        }
    }.build()

private data class TimelineGroup(val label: String, val entries: List<HistoryEntry>)

private fun groupByTimeline(entries: List<HistoryEntry>): List<TimelineGroup> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = cal.timeInMillis
    val weekAgo = now - 7 * 24 * 60 * 60 * 1000L
    val monthAgo = now - 30 * 24 * 60 * 60 * 1000L

    val today = mutableListOf<HistoryEntry>()
    val yesterday = mutableListOf<HistoryEntry>()
    val week = mutableListOf<HistoryEntry>()
    val month = mutableListOf<HistoryEntry>()
    val older = mutableListOf<HistoryEntry>()

    for (entry in entries) {
        when {
            entry.timestamp >= todayStart -> today.add(entry)
            entry.timestamp >= yesterdayStart -> yesterday.add(entry)
            entry.timestamp >= weekAgo -> week.add(entry)
            entry.timestamp >= monthAgo -> month.add(entry)
            else -> older.add(entry)
        }
    }

    return listOfNotNull(
        if (today.isNotEmpty()) TimelineGroup("今天", today) else null,
        if (yesterday.isNotEmpty()) TimelineGroup("昨天", yesterday) else null,
        if (week.isNotEmpty()) TimelineGroup("近一周", week) else null,
        if (month.isNotEmpty()) TimelineGroup("近一月", month) else null,
        if (older.isNotEmpty()) TimelineGroup("更久前", older) else null
    )
}

private fun formatEntryTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3600_000L -> "${diff / 60_000L}分钟前"
        diff < 86400_000L -> "${diff / 3600_000L}小时前"
        else -> {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timestamp
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            if (year == currentYear) "${month}月${day}日"
            else "${year}年${month}月${day}日"
        }
    }
}

// 帮助格式化纯日期的辅助函数
private fun formatDateOnly(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(Calendar.YEAR)}.${cal.get(Calendar.MONTH) + 1}.${cal.get(Calendar.DAY_OF_MONTH)}"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(navController: NavController) {
    var isExiting by remember { mutableStateOf(false) }

    // 系统返回键拦截
    BackHandler(enabled = !isExiting) {
        isExiting = true
        navController.popBackStack()
    }


    val historyList by HistoryUtil.getHistoryFlow().collectAsState(initial = emptyList())

    // 搜索与日期筛选状态
    var searchQuery by remember { mutableStateOf("") }
    var selectedStartDateMillis by remember { mutableStateOf<Long?>(null) }
    var selectedEndDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    var isManageMode by remember { mutableStateOf(false) }
    var selectedUrls by remember { mutableStateOf(setOf<String>()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val maxCount by GlobalData.historyMaxCount.collectAsState()
    val scope = rememberCoroutineScope()

    val pageBackground = darkThemeColor(light = Color(0xFFF5F5F5)) { background }

    // 将搜索词按空格分词，实现组合搜索
    val searchTerms = remember(searchQuery) {
        searchQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
    }

    // 核心筛选逻辑：组合过滤搜索词 + 日期/范围
    val filteredEntries =
        remember(historyList, searchTerms, selectedStartDateMillis, selectedEndDateMillis) {
            historyList.filter { entry ->
                // 1. 日期匹配 (精确计算到当天的 0点到24点)
                val matchesDate = if (selectedStartDateMillis != null) {
                    val startOfDay = Calendar.getInstance().apply {
                        timeInMillis = selectedStartDateMillis!!
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val endOfDay = Calendar.getInstance().apply {
                        timeInMillis = selectedEndDateMillis ?: selectedStartDateMillis!!
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    }.timeInMillis

                    entry.timestamp in startOfDay..endOfDay
                } else {
                    true
                }

                // 2. 关键词组合匹配
                val matchesSearch = if (searchTerms.isEmpty()) {
                    true
                } else {
                    searchTerms.all { term ->
                        entry.title.contains(term, ignoreCase = true) ||
                                entry.author.contains(term, ignoreCase = true) ||
                                entry.section.contains(term, ignoreCase = true)
                    }
                }

                matchesDate && matchesSearch
            }
        }

    val grouped = remember(filteredEntries) {
        groupByTimeline(filteredEntries)
    }

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 组合式日期选择器 Dialog
    if (showDatePicker) {
        var isRangeMode by remember {
            mutableStateOf(selectedEndDateMillis != null && selectedEndDateMillis != selectedStartDateMillis)
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedStartDateMillis ?: System.currentTimeMillis()
        )
        val dateRangePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = selectedStartDateMillis,
            initialSelectedEndDateMillis = selectedEndDateMillis
        )

        val customTitle: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选择日期",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                        color = if (!isRangeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.clickable { isRangeMode = false }
                    ) {
                        Text(
                            "单日",
                            fontSize = 13.sp,
                            color = if (!isRangeMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                        color = if (isRangeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.clickable { isRangeMode = true }
                    ) {
                        Text(
                            "范围",
                            fontSize = 13.sp,
                            color = if (isRangeMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    if (isRangeMode) {
                        selectedStartDateMillis = dateRangePickerState.selectedStartDateMillis
                        selectedEndDateMillis = dateRangePickerState.selectedEndDateMillis
                            ?: dateRangePickerState.selectedStartDateMillis
                    } else {
                        selectedStartDateMillis = datePickerState.selectedDateMillis
                        selectedEndDateMillis = datePickerState.selectedDateMillis
                    }
                    showDatePicker = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            }
        ) {
            if (isRangeMode) {
                DateRangePicker(
                    state = dateRangePickerState,
                    modifier = Modifier.weight(1f),
                    title = customTitle
                )
            } else {
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.weight(1f),
                    title = customTitle
                )
            }
        }
    }

    // 设置上限 Dialog
    if (showSettingsDialog) {
        var sliderValue by remember { mutableStateOf(maxCount.toFloat()) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("历史记录上限") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${sliderValue.toInt()} 条",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = (it / 100f).roundToInt() * 100f },
                        valueRange = 100f..2000f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "100",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "2000",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newCount = sliderValue.toInt().coerceIn(100, 2000)
                    GlobalData.historyMaxCount.value = newCount
                    SettingsUtil.saveHistoryMaxCount(newCount)
                    showSettingsDialog = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 清空确认 Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空浏览历史") },
            text = { Text("确定要清空所有浏览历史吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { HistoryUtil.clearHistory() }
                    showClearDialog = false
                    isManageMode = false
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "浏览历史",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!isExiting) {
                            isExiting = true
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (isManageMode) {
                        TextButton(onClick = {
                            isManageMode = false
                            selectedUrls = emptySet()
                        }) {
                            Text("完成", color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        if (historyList.isNotEmpty()) {
                            TextButton(onClick = { isManageMode = true }) {
                                Text("管理", color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { showClearDialog = true }) {
                                Text("清空", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { showSettingsDialog = true }) {
                            Text("设置", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageBackground
                )
            )
        },
        bottomBar = {
            if (isManageMode) {
                val allSelected =
                    filteredEntries.isNotEmpty() && selectedUrls.size == filteredEntries.size
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    color = pageBackground
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .padding(bottom = navBarPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedUrls = if (allSelected) emptySet()
                            else filteredEntries.map { it.url }.toSet()
                        }) {
                            Text(if (allSelected) "取消全选" else "全选")
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 ${selectedUrls.size} 项",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        HistoryUtil.batchDelete(selectedUrls.toList())
                                        selectedUrls = emptySet()
                                        isManageMode = false
                                    }
                                },
                                enabled = selectedUrls.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (selectedUrls.isNotEmpty()) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "删除",
                                    color = if (selectedUrls.isNotEmpty()) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = pageBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 4.dp),
                placeholder = { Text("支持组合查询", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(
                                CalendarIcon,
                                contentDescription = "按日期筛选",
                                tint = if (selectedStartDateMillis != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )

            // 日期过滤标签
            AnimatedVisibility(visible = selectedStartDateMillis != null) {
                selectedStartDateMillis?.let { startMillis ->
                    val endMillis = selectedEndDateMillis ?: startMillis
                    val dateStr = if (startMillis == endMillis) {
                        formatDateOnly(startMillis)
                    } else {
                        "${formatDateOnly(startMillis)} - ${formatDateOnly(endMillis)}"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.clickable {
                                selectedStartDateMillis = null
                                selectedEndDateMillis = null
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已筛选日期: $dateStr",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isNotBlank() || selectedStartDateMillis != null) "没有匹配的记录" else "暂无浏览记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { group ->
                        stickyHeader(key = group.label) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = pageBackground
                            ) {
                                Text(
                                    text = group.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 10.dp
                                    )
                                )
                            }
                        }

                        items(
                            items = group.entries,
                            key = { it.url }
                        ) { entry ->
                            val isSelected = entry.url in selectedUrls
                            val bgColor by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                } else Color.Transparent,
                                animationSpec = tween(150),
                                label = "selectBg"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    // 【修改点】移除了原先复杂的 then(if(isExiting)) 逻辑，还原最干净的高性能可点击区域
                                    .clickable {
                                        if (isManageMode) {
                                            selectedUrls = if (isSelected) {
                                                selectedUrls - entry.url
                                            } else {
                                                selectedUrls + entry.url
                                            }
                                        } else {
                                            val encodedUrl = URLEncoder.encode(entry.url, "utf-8")
                                            navController.navigate("MineHistoryPostPage?url=$encodedUrl")
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isManageMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            selectedUrls = if (isSelected) {
                                                selectedUrls - entry.url
                                            } else {
                                                selectedUrls + entry.url
                                            }
                                        },
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val metaText = buildString {
                                            if (entry.author.isNotBlank()) append(entry.author)
                                            if (entry.author.isNotBlank() && entry.section.isNotBlank()) append(
                                                " · "
                                            )
                                            if (entry.section.isNotBlank()) append(entry.section)
                                        }
                                        if (metaText.isNotBlank()) {
                                            Text(
                                                text = metaText,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = formatEntryTime(entry.timestamp),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.7f
                                            )
                                        )
                                    }
                                }

                                if (!isManageMode) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                HistoryUtil.deleteEntry(entry.url)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.4f
                                            ),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(navBarPadding + 16.dp))
                    }
                }
            }
        }
    }
}