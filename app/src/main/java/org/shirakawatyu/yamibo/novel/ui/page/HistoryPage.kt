package org.shirakawatyu.yamibo.novel.ui.page

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.bean.HistoryEntry
import org.shirakawatyu.yamibo.novel.util.history.HistoryUtil
import java.net.URLEncoder
import java.util.Calendar

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(navController: NavController) {
    val historyList by HistoryUtil.getHistoryFlow().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var isManageMode by remember { mutableStateOf(false) }
    var selectedUrls by remember { mutableStateOf(setOf<String>()) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filteredEntries = remember(historyList, searchQuery) {
        if (searchQuery.isBlank()) historyList
        else historyList.filter { entry ->
            entry.title.contains(searchQuery, ignoreCase = true) ||
                    entry.author.contains(searchQuery, ignoreCase = true) ||
                    entry.section.contains(searchQuery, ignoreCase = true)
        }
    }

    val grouped = remember(filteredEntries) {
        groupByTimeline(filteredEntries)
    }

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Clear confirmation dialog
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
                    IconButton(onClick = { navController.popBackStack() }) {
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
                    } else if (historyList.isNotEmpty()) {
                        TextButton(onClick = { isManageMode = true }) {
                            Text("管理", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (isManageMode) {
                val allSelected = filteredEntries.isNotEmpty() && selectedUrls.size == filteredEntries.size
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
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
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索标题、作者、分区…", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
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

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isNotBlank()) "没有匹配的记录" else "暂无浏览记录",
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
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Text(
                                    text = group.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
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
                                    .clickable {
                                        if (isManageMode) {
                                            selectedUrls = if (isSelected) {
                                                selectedUrls - entry.url
                                            } else {
                                                selectedUrls + entry.url
                                            }
                                        } else {
                                            val encodedUrl = URLEncoder.encode(entry.url, "utf-8")
                                            navController.navigate("OtherWebPage/$encodedUrl")
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
                                            if (entry.author.isNotBlank() && entry.section.isNotBlank()) append(" · ")
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                // Swipe hint or delete button in manage mode
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
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
