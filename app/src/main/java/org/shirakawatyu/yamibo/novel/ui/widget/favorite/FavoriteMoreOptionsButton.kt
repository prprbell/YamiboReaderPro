package org.shirakawatyu.yamibo.novel.ui.widget.favorite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.darkThemeColor


@Composable
internal fun FavoriteMoreOptionsButton(
    topBarContentColor: Color,
    isLoggedIn: Boolean,
    isRefreshing: Boolean,
    isFavoriteCollapsed: Boolean,
    isClickToTopEnabled: Boolean,
    isDnsOptimizationEnabled: Boolean,
    onSetHomePage: () -> Unit,
    onManageFavorite: () -> Unit,
    onToggleFavoriteCollapsed: () -> Unit,
    onManageCache: () -> Unit,
    onToggleClickToTop: () -> Unit,
    onManageBookmark: () -> Unit,
    onNetworkOptimization: () -> Unit,
    onManageDirectory: () -> Unit,
    onToggleAutoSignIn: () -> Unit,
    onRefreshList: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var lastMenuClickTime by remember { mutableLongStateOf(0L) }
    val activeMenuContainer = MaterialTheme.colorScheme.primary
    val activeMenuContent = MaterialTheme.colorScheme.onPrimary
    val activeMenuText = darkThemeColor(YamiboColors.primary) { primary }

    @Composable
    fun ActiveMenuIcon(active: Boolean, content: @Composable (Color) -> Unit) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(if (active) Modifier.background(activeMenuContainer) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            content(if (active) activeMenuContent else MaterialTheme.colorScheme.onSurface)
        }
    }

    fun runMenuAction(action: () -> Unit) {
        menuExpanded = false
        action()
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMenuClickTime > 250L) {
                    lastMenuClickTime = currentTime
                    menuExpanded = true
                }
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_more_horiz),
                contentDescription = "更多选项",
                modifier = Modifier.size(24.dp),
                tint = topBarContentColor
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(x = 9.dp, y = 16.dp),
            modifier = Modifier
                .width(256.dp)
                .background(MaterialTheme.colorScheme.surface)
                .clip(RoundedCornerShape(12.dp))
        ) {
            // 第一排：设置首页 管理收藏
            Row(modifier = Modifier.fillMaxWidth()) {
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("设置首页") },
                    onClick = { runMenuAction(onSetHomePage) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Home,
                            null,
                            Modifier.size(24.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("管理收藏") },
                    enabled = isLoggedIn,
                    onClick = { runMenuAction(onManageFavorite) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_visibility),
                            null,
                            Modifier.size(24.dp),
                            tint = if (isLoggedIn) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    })
            }

            // 第二排：折叠 管理缓存
            Row(modifier = Modifier.fillMaxWidth()) {
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = {
                        Text(
                            text = if (isFavoriteCollapsed) "关闭折叠" else "折叠模式",
                            color = if (isFavoriteCollapsed) activeMenuText else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { runMenuAction(onToggleFavoriteCollapsed) },
                    leadingIcon = {
                        ActiveMenuIcon(isFavoriteCollapsed) { tint ->
                            Icon(
                                painterResource(id = if (isFavoriteCollapsed) R.drawable.ic_unfold_more else R.drawable.ic_unfold_less),
                                null,
                                Modifier.size(18.dp),
                                tint = tint
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("管理缓存") },
                    onClick = { runMenuAction(onManageCache) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            null,
                            Modifier.size(24.dp)
                        )
                    }
                )
            }

            // 第三排：阅后置顶 管理书签
            Row(modifier = Modifier.fillMaxWidth()) {
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = {
                        Text(
                            text = if (isClickToTopEnabled) "关闭置顶" else "阅后置顶",
                            color = if (isClickToTopEnabled) activeMenuText else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { runMenuAction(onToggleClickToTop) },
                    leadingIcon = {
                        ActiveMenuIcon(isClickToTopEnabled) { tint ->
                            Icon(
                                painter = painterResource(id = R.drawable.ic_align_top),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tint
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("管理书签") },
                    onClick = { runMenuAction(onManageBookmark) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.DateRange,
                            null,
                            Modifier.size(24.dp)
                        )
                    }
                )
            }

            // 第四排：网络优化 管理目录
            Row(modifier = Modifier.fillMaxWidth()) {
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = {
                        Text(
                            text = "网络优化",
                            color = if (isDnsOptimizationEnabled) activeMenuText else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { runMenuAction(onNetworkOptimization) },
                    leadingIcon = {
                        ActiveMenuIcon(isDnsOptimizationEnabled) { tint ->
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tint
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("管理目录") },
                    onClick = { runMenuAction(onManageDirectory) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            null,
                            Modifier.size(24.dp)
                        )
                    }
                )
            }

            // 第五排：自动签到 刷新
            Row(modifier = Modifier.fillMaxWidth()) {
                val isAutoSignIn = GlobalData.isAutoSignInEnabled.value
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = {
                        Text(
                            text = if (isAutoSignIn) "关闭签到" else "自动签到",
                            color = if (isAutoSignIn) activeMenuText else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { runMenuAction(onToggleAutoSignIn) },
                    leadingIcon = {
                        ActiveMenuIcon(isAutoSignIn) { tint ->
                            Icon(
                                imageVector = if (isAutoSignIn) Icons.Default.Clear
                                else Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tint
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.weight(1f),
                    text = { Text("刷新列表") },
                    onClick = { runMenuAction(onRefreshList) },
                    enabled = !isRefreshing,
                    leadingIcon = {
                        if (isRefreshing) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(
                            Icons.Default.Refresh,
                            null,
                            Modifier.size(24.dp)
                        )
                    }
                )
            }
        }
    }
}
