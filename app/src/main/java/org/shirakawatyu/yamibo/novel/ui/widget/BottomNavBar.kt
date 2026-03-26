package org.shirakawatyu.yamibo.novel.ui.widget

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM

@Composable
fun BottomNavBar(
    navController: NavController,
    currentRoute: String?,
    navBarVM: BottomNavBarVM
) {
    if (!navBarVM.showBottomNavBar) return
    val uiState by navBarVM.uiState.collectAsState()
    val webProgress by GlobalData.webProgress.collectAsState()
    val pageList = listOf("FavoritePage", "BBSPage", "MinePage")

    val animatedProgress = remember { Animatable(0f) }

    // ==== 下拉刷新动画状态 ====
    var showRefreshPopup by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }

    val refreshProgress = remember { Animatable(0f) }
    val rotationAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val density = LocalDensity.current
    val maxDragPx = with(density) { 130.dp.toPx() }
    val view = LocalView.current
    var hasVibrated by remember { mutableStateOf(false) }

    // 达标时触发震动反馈
    LaunchedEffect(refreshProgress.value) {
        if (refreshProgress.value >= 0.8f && !hasVibrated && !isRefreshing) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            hasVibrated = true
        } else if (refreshProgress.value < 0.8f) {
            hasVibrated = false
        }
    }

    LaunchedEffect(webProgress) {
        val target = webProgress.toFloat() / 100f
        if (target < animatedProgress.value || target == 0f) {
            animatedProgress.snapTo(target)
        } else {
            animatedProgress.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 250, easing = LinearEasing)
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {

        // 上拉刷新视觉气泡
        AnimatedVisibility(
            visible = showRefreshPopup,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val selectedIndex = pageList.indexOf(currentRoute).coerceAtLeast(0)
            val currentProgress = refreshProgress.value

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(
                        y = 0.dp - (135.dp * kotlin.math.sin(currentProgress * kotlin.math.PI / 2)
                            .toFloat())
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.icons.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (index == selectedIndex) {
                            val isReady = currentProgress >= 0.8f

                            val scale by animateFloatAsState(
                                targetValue = if (isReady) 1.1f else 1.0f + (currentProgress * 0.1f),
                                animationSpec = tween(150),
                                label = "scale"
                            )
                            val iconAlpha by animateFloatAsState(
                                targetValue = if (isReady) 1f else 0.6f + (currentProgress * 0.4f),
                                label = "alpha"
                            )

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .scale(scale)
                                    .shadow(
                                        elevation = if (isReady) 6.dp else 2.dp,
                                        shape = CircleShape,
                                        ambientColor = YamiboColors.primary.copy(alpha = 0.05f),
                                        spotColor = YamiboColors.primary.copy(alpha = 0.15f)
                                    )
                                    .background(YamiboColors.onSurface, CircleShape)
                                    .border(
                                        0.5.dp,
                                        YamiboColors.primary.copy(alpha = 0.15f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = YamiboColors.primary.copy(alpha = iconAlpha),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .rotate(rotationAnim.value)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 导航栏层
        NavigationBar(
            Modifier.height(50.dp),
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = YamiboColors.onSurface
        ) {
            uiState.icons.forEachIndexed { index, item ->
                val targetRoute = pageList[index]
                val isSelected = currentRoute == targetRoute
                NavigationBarItem(
                    icon = { Icon(item, contentDescription = "") },
                    selected = isSelected,
                    colors = NavigationBarItemDefaults.colors(indicatorColor = YamiboColors.tertiary),
                    onClick = {
                        if (currentRoute == targetRoute) return@NavigationBarItem
                        navBarVM.changeSelection(index, navController)
                    },
                    modifier = Modifier.pointerInput(isSelected) {
                        // 取消FavoritePage的刷新功能
                        if (!isSelected || targetRoute == "FavoritePage") return@pointerInput

                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (isRefreshing) return@detectDragGesturesAfterLongPress
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                showRefreshPopup = true
                                dragOffset = 0f
                                coroutineScope.launch {
                                    refreshProgress.snapTo(0f)
                                    rotationAnim.snapTo(0f)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (isRefreshing) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragOffset += dragAmount.y
                                coroutineScope.launch {
                                    val progress = (-dragOffset / maxDragPx).coerceIn(0f, 1f)
                                    refreshProgress.snapTo(progress)
                                    rotationAnim.snapTo(progress * 360f * 1.5f)
                                }
                            },
                            onDragEnd = {
                                if (isRefreshing) return@detectDragGesturesAfterLongPress
                                val isReady = refreshProgress.value >= 0.8f

                                if (isReady) {
                                    isRefreshing = true
                                    currentRoute?.let { navBarVM.triggerRefresh(it) }

                                    coroutineScope.launch {
                                        // 悬浮在最高点
                                        launch {
                                            refreshProgress.animateTo(
                                                1f,
                                                tween(200, easing = FastOutSlowInEasing)
                                            )
                                        }

                                        // 开启转圈动画
                                        val spinJob = launch {
                                            while (isRefreshing) {
                                                rotationAnim.animateTo(
                                                    targetValue = rotationAnim.value + 360f,
                                                    animationSpec = tween(
                                                        800,
                                                        easing = LinearEasing
                                                    )
                                                )
                                            }
                                        }

                                        // 监听网页加载进度
                                        delay(300)
                                        withTimeoutOrNull(8000) {
                                            snapshotFlow { webProgress }.first { it >= 100 }
                                        }

                                        isRefreshing = false
                                        spinJob.cancel()

                                        showRefreshPopup =
                                            false

                                        delay(250)
                                        refreshProgress.snapTo(0f)
                                        rotationAnim.snapTo(0f)
                                    }
                                } else {
                                    coroutineScope.launch {
                                        launch {
                                            rotationAnim.animateTo(
                                                0f,
                                                tween(300, easing = FastOutSlowInEasing)
                                            )
                                        }
                                        refreshProgress.animateTo(
                                            0f,
                                            tween(300, easing = FastOutSlowInEasing)
                                        )
                                        showRefreshPopup = false
                                    }
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                if (isRefreshing) return@detectDragGesturesAfterLongPress
                                coroutineScope.launch {
                                    launch {
                                        rotationAnim.animateTo(
                                            0f,
                                            tween(300, easing = FastOutSlowInEasing)
                                        )
                                    }
                                    refreshProgress.animateTo(
                                        0f,
                                        tween(300, easing = FastOutSlowInEasing)
                                    )
                                    showRefreshPopup = false
                                }
                                dragOffset = 0f
                            }
                        )
                    }
                )
            }
        }

        // 网页进度条
        AnimatedVisibility(
            visible = webProgress > 0 && animatedProgress.value < 1f &&
                    (currentRoute == "BBSPage" || currentRoute == "MinePage"),
            enter = fadeIn(tween(200)) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(200)
            ),
            exit = fadeOut(tween(300)) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(300)
            ),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = YamiboColors.primary,
                trackColor = YamiboColors.primary.copy(alpha = 0.1f),
                strokeCap = StrokeCap.Round
            )
        }
    }
}