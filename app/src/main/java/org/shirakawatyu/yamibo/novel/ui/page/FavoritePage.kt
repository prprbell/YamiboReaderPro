package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.item.FavoriteItem
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.TopBar
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 收藏页面，展示用户的收藏列表，支持刷新和拖拽排序。
 *
 * @param favoriteVM 用于管理收藏数据的 ViewModel，默认通过 viewModel() 获取实例。
 * @param navController 导航控制器，用于跳转到其他页面。
 */
@Composable
fun FavoritePage(
    favoriteVM: FavoriteVM = viewModel(factory = ViewModelFactory(LocalContext.current.applicationContext)),
    navController: NavController
) {
    val uiState by favoriteVM.uiState.collectAsState()
    val favoriteList = uiState.favoriteList
    val isRefreshing = uiState.isRefreshing
    val isInManageMode = uiState.isInManageMode
    val selectedItems = uiState.selectedItems
    var cacheInfoMap = uiState.cacheInfoMap
    var showCacheManagement by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { favoriteVM.refreshCacheInfo() }

    var showBookmarkManagement by remember { mutableStateOf(false) }
    var showDirectoryManagement by remember { mutableStateOf(false) }
    var directoryList by remember { mutableStateOf<List<MangaDirectory>>(emptyList()) }

    SetStatusBarColor(YamiboColors.onSurface)

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                favoriteVM.refreshList(showLoading = false)
                favoriteVM.getCacheInfo { info -> cacheInfoMap = info }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            if (!isInManageMode) favoriteVM.moveFavorite(from.index, to.index)
        }
    )

    // 分类数据
    val categoryOptions = listOf(
        Triple(-1, "全部", Color.Transparent),
        Triple(1, "小说", Color(0xFF4CAF50)),
        Triple(2, "漫画", Color(0xFF2196F3)),
        Triple(3, "其他", Color(0xFFFF9800)),
        Triple(0, "未定", Color(0xFF9E9E9E))
    )
    val currentCat =
        categoryOptions.find { it.first == favoriteVM.currentCategory } ?: categoryOptions[0]

    Column {
        TopBar(title = "") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // =============== 左半部：标题或分类切换 ===============
                if (isInManageMode) {
                    Text(
                        text = "管理收藏 (${selectedItems.size})",
                        fontSize = 20.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    var categoryMenuExpanded by remember { mutableStateOf(false) }
                    val arrowRotation by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (categoryMenuExpanded) 180f else 0f,
                        animationSpec = tween(
                            durationMillis = 300,
                            easing = FastOutSlowInEasing
                        ),
                        label = "arrow_rotation_animation"
                    )
                    Box(modifier = Modifier.padding(start = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { categoryMenuExpanded = true }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "收藏栏/${currentCat.second}",
                                fontSize = 20.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                painterResource(R.drawable.ic_arrow_down),
                                contentDescription = if (categoryMenuExpanded) "收起分类" else "展开分类",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .offset(y = 4.dp)
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(
                                            pivotFractionX = 0.5f,
                                            pivotFractionY = 0.5f
                                        )
                                        rotationZ = arrowRotation
                                    }
                            )
                        }

                        // 下拉菜单
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                            offset = DpOffset(x = 0.dp, y = 16.dp),
                            modifier = Modifier
                                .width(140.dp)
                                .background(Color(0xFFFFFCF0))
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            categoryOptions.forEach { (typeId, name, color) ->
                                DropdownMenuItem(
                                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                                    text = {
                                        Text(
                                            text = name,
                                            fontSize = 16.sp,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    },
                                    onClick = {
                                        favoriteVM.setCategory(typeId)
                                        categoryMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier.size(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (typeId == -1) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.List,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .background(
                                                            color,
                                                            androidx.compose.foundation.shape.CircleShape
                                                        )
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                // =============== 右半部：操作菜单区域 ===============
                if (isInManageMode) {
                    Button(
                        onClick = { favoriteVM.hideSelectedItems() },
                        enabled = selectedItems.isNotEmpty(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.67f
                            )
                        )
                    ) { Text("隐藏") }
                    Spacer(modifier = Modifier.width(20.dp))
                    Button(
                        onClick = { favoriteVM.unhideSelectedItems() },
                        enabled = selectedItems.isNotEmpty(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.67f
                            )
                        )
                    ) { Text("显示") }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(onClick = { favoriteVM.toggleManageMode() }) { Text("完成") }
                } else {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_horiz),
                                contentDescription = "更多选项",
                                modifier = Modifier.size(24.dp),
                                tint = YamiboColors.primary
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            offset = DpOffset(x = 9.dp, y = 16.dp),
                            modifier = Modifier
                                .background(Color(0xFFFFFCF0))
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            // 此处的五个原有选项代码不变：管理缓存、管理书签、管理目录、管理收藏、刷新列表
                            DropdownMenuItem(
                                text = { Text("管理缓存") },
                                onClick = { showCacheManagement = true; menuExpanded = false },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_download),
                                        null,
                                        Modifier.size(24.dp)
                                    )
                                })
                            DropdownMenuItem(
                                text = { Text("管理书签") },
                                onClick = { showBookmarkManagement = true; menuExpanded = false },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DateRange,
                                        null,
                                        Modifier.size(24.dp)
                                    )
                                })
                            DropdownMenuItem(
                                text = { Text("管理目录") },
                                onClick = {
                                    favoriteVM.getDirectoryList { dirs ->
                                        directoryList = dirs; showDirectoryManagement = true
                                    }; menuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        null,
                                        Modifier.size(24.dp)
                                    )
                                })
                            DropdownMenuItem(
                                text = { Text("管理收藏") },
                                onClick = { favoriteVM.toggleManageMode(); menuExpanded = false },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_visibility),
                                        null,
                                        Modifier.size(24.dp)
                                    )
                                })
                            DropdownMenuItem(
                                text = { Text("刷新列表") },
                                onClick = { favoriteVM.refreshList(); menuExpanded = false },
                                enabled = !isRefreshing,
                                leadingIcon = {
                                    if (isRefreshing) CircularProgressIndicator(
                                        Modifier.size(
                                            24.dp
                                        )
                                    ) else Icon(Icons.Default.Refresh, null, Modifier.size(24.dp))
                                }
                            )
                        }
                    }
                }
            }
        }

        // LazyColumn (收藏列表)
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.padding(0.dp, 3.dp)
        ) {

            itemsIndexed(
                items = favoriteList,
                key = { _, item -> item.url }
            ) { index, item ->
                // 使用ReorderableItem包装item以支持拖动排序
                ReorderableItem(
                    state = reorderableState,
                    key = item.url,
                ) { isDragging ->
                    val isSelected = selectedItems.contains(item.url)
                    FavoriteItem(
                        item.title,
                        item.lastView,
                        item.lastPage,
                        item.lastChapter,
                        onClick = {
                            if (isInManageMode) {
                                favoriteVM.toggleItemSelection(item.url)
                            } else {
                                // 传入 item.type
                                favoriteVM.clickHandler(item, navController)
                            }
                        },
                        // 拖拽手柄只在非管理模式下启用
                        modifier = Modifier.longPressDraggableHandle(
                            enabled = !isInManageMode,
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        ),
                        isDragging = isDragging,
                        // 传递管理状态
                        isManageMode = isInManageMode,
                        isSelected = isSelected,
                        isHidden = item.isHidden,
                        type = item.type,
                        cacheInfo = cacheInfoMap[item.url],
                        // 拖拽手柄只在非管理模式下显示
                        dragHandle = {
                            if (!isInManageMode) {
                                Icon(
                                    Icons.Filled.Menu,
                                    contentDescription = "Reorder",
                                    tint = YamiboColors.primary
                                )
                            }
                        }
                    )
                }
            }
        }

        // 缓存管理对话框
        if (showCacheManagement) {
            CacheManagementDialog(
                favoriteList = favoriteList,
                cacheInfoMap = cacheInfoMap,
                onDismiss = { showCacheManagement = false },
                onDeleteCache = { url ->
                    favoriteVM.deleteFavoriteCache(url)
                },
                onClearAll = {
                    favoriteVM.clearAllCache()
                }
            )
        }
        // 书签管理对话框
        if (showBookmarkManagement) {
            BookmarkManagementDialog(
                favoriteList = favoriteList,
                onDismiss = { showBookmarkManagement = false },
                onClearBookmark = { url ->
                    favoriteVM.clearBookmark(url)
                },
                onClearAll = {
                    favoriteVM.clearAllBookmarks()
                }
            )
        }
        // 目录管理对话框
        if (showDirectoryManagement) {
            DirectoryManagementDialog(
                directoryList = directoryList,
                onDismiss = { showDirectoryManagement = false },
                onDeleteDirectory = { cleanName ->
                    favoriteVM.deleteDirectory(cleanName) {
                        // 删除后重新拉取列表以刷新 UI
                        favoriteVM.getDirectoryList { dirs -> directoryList = dirs }
                    }
                },
                onClearAll = {
                    favoriteVM.clearAllDirectories() {
                        directoryList = emptyList()
                    }
                }
            )
        }
    }
}

// 在文件末尾添加缓存管理对话框
@Composable
fun CacheManagementDialog(
    favoriteList: List<Favorite>,
    cacheInfoMap: Map<String, FavoriteVM.CacheInfo>,
    onDismiss: () -> Unit,
    onDeleteCache: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 格式化文件大小的辅助函数
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

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
                // 统计信息
                val totalCached = cacheInfoMap.values.sumOf { it.totalPages }
                val totalSize = cacheInfoMap.values.sumOf { it.totalSize }

                if (cacheInfoMap.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "总计: ${cacheInfoMap.size} 部作品",
                                fontSize = 14.sp
                            )
                            Text(
                                "缓存页数: $totalCached 页",
                                fontSize = 14.sp
                            )
                            Text(
                                "占用空间: ${formatFileSize(totalSize)}",
                                fontSize = 14.sp
                            )
                        }
                    }

                    HorizontalDivider()

                    // 作品列表
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 分离数据
                        val favoriteUrls = favoriteList.map { it.url }.toSet()

                        // 仍在收藏中的缓存 (有标题)
                        val favoriteCaches =
                            favoriteList.filter { cacheInfoMap.containsKey(it.url) }

                        // 已移除的缓存 (孤立的)
                        val orphanedCaches =
                            cacheInfoMap.values.filter { !favoriteUrls.contains(it.url) }

                        items(
                            items = favoriteCaches,
                            key = { "favorite_${it.url}" }
                        ) { favorite ->
                            val info = cacheInfoMap[favorite.url]!!
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        favorite.title, // 使用收藏标题
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${info.totalPages} 页 · ${formatFileSize(info.totalSize)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteCache(favorite.url) }) {
                                    Icon(
                                        Icons.Default.Delete, "删除缓存",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        // 3. 渲染孤立的缓存
                        if (orphanedCaches.isNotEmpty()) {
                            item(key = "orphan_header") {
                                Text(
                                    "已取消收藏的帖子的缓存",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(
                                items = orphanedCaches,
                                key = { "orphan_${it.url}" }
                            ) { info ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            info.url, // 只能显示 URL
                                            fontSize = 12.sp, // 用小号字体显示 URL
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "${info.totalPages} 页 · ${formatFileSize(info.totalSize)}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onDeleteCache(info.url) }) {
                                        Icon(
                                            Icons.Default.Delete, "删除缓存",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    // 无缓存提示
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_cache),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "暂无缓存",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在阅读页面点击缓存按钮\n即可开始缓存小说",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cacheInfoMap.isNotEmpty()) {
                TextButton(
                    onClick = { showClearAllConfirm = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空所有缓存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // 清空确认对话框
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空所有缓存吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearAllConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// 在文件末尾添加书签管理对话框
@Composable
fun BookmarkManagementDialog(
    favoriteList: List<Favorite>,
    onDismiss: () -> Unit,
    onClearBookmark: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 筛选出存在阅读进度的收藏 (只要页数大于0、网页大于1、有最后章节名，或者有漫画URL，就算有进度)
    val bookmarkedList = favoriteList.filter {
        it.lastPage > 0 || it.lastView > 1 || !it.lastChapter.isNullOrEmpty() || !it.lastMangaUrl.isNullOrEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "书签与进度管理",
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
                if (bookmarkedList.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "总计: ${bookmarkedList.size} 个书签/进度",
                                fontSize = 14.sp
                            )
                        }
                    }

                    HorizontalDivider()

                    // 有书签的作品列表
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(
                            items = bookmarkedList,
                            key = { it.url }
                        ) { favorite ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        favorite.title,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    // 优先显示章节名，没有的话显示页数信息
                                    val progressText = if (!favorite.lastChapter.isNullOrEmpty()) {
                                        favorite.lastChapter!!
                                    } else {
                                        "第${favorite.lastPage + 1}页 / 网页第${favorite.lastView}页"
                                    }
                                    Text(
                                        progressText,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onClearBookmark(favorite.url) }) {
                                    Icon(
                                        Icons.Default.Delete, "删除书签",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    // 无进度提示
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "暂无书签/进度",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "阅读过的小说或漫画进度\n将会在这里显示",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (bookmarkedList.isNotEmpty()) {
                TextButton(
                    onClick = { showClearAllConfirm = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空所有书签")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // 清空确认对话框
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空所有书签和阅读进度吗？\n此操作不可撤销，阅读进度将会重置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearAllConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}// 在文件末尾添加目录管理对话框

@Composable
fun DirectoryManagementDialog(
    directoryList: List<MangaDirectory>,
    onDismiss: () -> Unit,
    onDeleteDirectory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 实现本地搜索过滤
    val filteredList = if (searchQuery.isBlank()) {
        directoryList
    } else {
        directoryList.filter { it.cleanBookName.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "漫画目录管理",
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
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索漫画名称...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                            }
                        }
                    }
                )

                if (directoryList.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "总计: ${directoryList.size} 个本地目录",
                                fontSize = 14.sp
                            )
                            if (searchQuery.isNotBlank()) {
                                Text(
                                    "搜索结果: ${filteredList.size} 个",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 过滤后的目录列表
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(
                            items = filteredList,
                            key = { it.cleanBookName }
                        ) { dir ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        dir.cleanBookName,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDeleteDirectory(dir.cleanBookName) }) {
                                    Icon(
                                        Icons.Default.Delete, "删除目录",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    // 无目录提示
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "暂无本地目录",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (directoryList.isNotEmpty()) {
                TextButton(
                    onClick = { showClearAllConfirm = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空所有目录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // 清空确认对话框
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空所有本地保存的漫画目录吗？\n此操作不可撤销，下次进入漫画时将重新抓取并生成目录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearAllConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}