package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.R

/**
 * 缓存管理对话框
 */
@Composable
fun CacheDialog(
    maxWebView: Int,
    cachedPages: Set<Int>,
    onDismiss: () -> Unit,
    onStartCache: (pages: Set<Int>, includeImages: Boolean) -> Unit,
    onDeleteCache: (pages: Set<Int>) -> Unit,
    onUpdateCache: (pages: Set<Int>, includeImages: Boolean) -> Unit
) {
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var includeImages by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "缓存管理",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // 操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 全选按钮
                    TextButton(
                        onClick = {
                            selectedPages = if (selectedPages.size == maxWebView) {
                                emptySet()
                            } else {
                                (1..maxWebView).toSet()
                            }
                        }
                    ) {
                        Text(
                            if (selectedPages.size == maxWebView) "取消全选" else "全选",
                            fontSize = 14.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Text(
                            "仅能缓存为无图的纯文本",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    }
                    // 图片开关
//                    Row(
//                        verticalAlignment = Alignment.CenterVertically,
//                        modifier = Modifier.padding(end = 8.dp)
//                    ) {
//                        Text(
//                            "缓存图片",
//                            fontSize = 14.sp,
//                            modifier = Modifier.padding(end = 8.dp)
//                        )
//                        Switch(
//                            checked = includeImages,
//                            onCheckedChange = { includeImages = it }
//                        )
//                    }
                }

                HorizontalDivider()

                // 页面列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = lazyListState
                ) {
                    items((1..maxWebView).toList()) { pageNum ->
                        val isCached = cachedPages.contains(pageNum)
                        val isSelected = selectedPages.contains(pageNum)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPages = if (isSelected) {
                                        selectedPages - pageNum
                                    } else {
                                        selectedPages + pageNum
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp)
                                .background(
                                    if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else
                                        Color.Transparent
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedPages = if (it) {
                                            selectedPages + pageNum
                                        } else {
                                            selectedPages - pageNum
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "第 $pageNum 页",
                                    fontSize = 16.sp
                                )
                            }

                            if (isCached) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "已缓存",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (pageNum < maxWebView) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }

                // 统计信息
                if (selectedPages.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "已选择 ${selectedPages.size} 页",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 删除按钮
                if (selectedPages.any { cachedPages.contains(it) }) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }

                // 更新按钮
                if (selectedPages.any { cachedPages.contains(it) }) {
                    TextButton(
                        onClick = {
                            onUpdateCache(selectedPages, includeImages)
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("更新")
                    }
                }

                // 缓存按钮
                TextButton(
                    onClick = {
                        val pagesToCache =
                            selectedPages.filter { !cachedPages.contains(it) }.toSet()
                        if (pagesToCache.isNotEmpty()) {
                            onStartCache(pagesToCache, includeImages)
                        }
                    },
                    enabled = selectedPages.any { !cachedPages.contains(it) }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cache),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("缓存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedPages.filter { cachedPages.contains(it) }.size} 页缓存吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCache(selectedPages.filter { cachedPages.contains(it) }.toSet())
                        showDeleteConfirm = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 缓存进度对话框
 */
@Composable
fun CacheProgressDialog(
    totalPages: Int,
    currentPage: Int,
    currentPageNum: Int,
    onDismiss: () -> Unit,
    onStopCache: () -> Unit
) {
    val progress = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f
    val isComplete = currentPage >= totalPages

    AlertDialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        title = {
            Text(
                if (isComplete) "缓存完成" else "正在缓存", // [MODIFIED]
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(64.dp),
                )

                Spacer(Modifier.height(16.dp))

                // [MODIFIED] 根据是否完成显示不同文本
                if (isComplete) {
                    Text(
                        "已完成 $totalPages / $totalPages 页",
                        fontSize = 16.sp
                    )
                } else {
                    Text(
                        "正在缓存第 $currentPageNum 页",
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$currentPage / $totalPages",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (isComplete) {
                TextButton(onClick = onDismiss) {
                    Text("完成")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("后台运行")
                }
            }
        },
        dismissButton = {
            if (!isComplete) {
                TextButton(
                    onClick = onStopCache,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("终止")
                }
            }
        }
    )
}