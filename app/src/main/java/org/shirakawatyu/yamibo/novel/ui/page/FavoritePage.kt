package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.item.FavoriteItem
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
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
    favoriteVM: FavoriteVM = viewModel(),
    navController: NavController
) {
    val uiState by favoriteVM.uiState.collectAsState()
    // 获取收藏列表
    val favoriteList = uiState.favoriteList
    // 获取收藏列表的刷新状态
    val isRefreshing = uiState.isRefreshing
    SetStatusBarColor(YamiboColors.onSurface)

    // 监听生命周期，在 onResume 时重新加载和刷新收藏列表
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                favoriteVM.loadFavorites()
                favoriteVM.refreshList(showLoading = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // 长按触觉反馈
    val hapticFeedback = LocalHapticFeedback.current
    // implementation("sh.calvin.reorderable:reorderable:3.0.0")
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            favoriteVM.moveFavorite(from.index, to.index)
        }
    )
    Column {
        TopBar(title = "收藏") {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp), // 使用更小的尺寸
                    color = YamiboColors.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Button(onClick = { favoriteVM.refreshList() }) {
                    Icon(Icons.Filled.Refresh, "")
                }
            }
        }
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
                    FavoriteItem(
                        item.title,
                        item.lastView,
                        item.lastPage,
                        item.lastChapter,
                        onClick = {
                            favoriteVM.clickHandler(item.url, navController)
                        },
                        modifier = Modifier.longPressDraggableHandle(onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }),
                        isDragging = isDragging,
                        dragHandle = {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Reorder",
                                tint = YamiboColors.primary
                            )
                        }
                    )
                }
            }
        }
    }
}