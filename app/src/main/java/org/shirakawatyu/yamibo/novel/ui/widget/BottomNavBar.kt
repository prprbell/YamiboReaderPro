package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.util.HapticUtil
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 操作槽位 — 决定按钮在导航栏上方的水平位置与垂直偏移 */
enum class ActionSlot(val labelX: Float, val labelYOffset: Float) {
    Left(-85f, 15f),
    Center(0f, -20f),
    Right(85f, 15f)
}

/** 操作类型 — 决定松手后的执行逻辑 */
enum class ActionKind { Home, Refresh, DarkMode }

/** 单个快捷操作的配置 */
data class QuickAction(
    val slot: ActionSlot,
    val icon: ImageVector,
    val description: String,
    val kind: ActionKind,
    val iconResId: Int? = null   // 非 null 时优先使用此资源，支持夜间模式动态切换图标
)

/** 根据当前路由和夜间模式状态返回该页面允许的快捷操作列表 */
private fun getQuickActions(route: String?, isDarkMode: Boolean): List<QuickAction> {
    return when (route) {
        "BBSPage" -> listOf(
            QuickAction(ActionSlot.Left, Icons.Default.Home, "返回首页", ActionKind.Home),
            QuickAction(
                ActionSlot.Center,
                Icons.Default.Settings,
                "夜间模式",
                ActionKind.DarkMode,
                iconResId = if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon
            ),
            QuickAction(ActionSlot.Right, Icons.Default.Refresh, "刷新", ActionKind.Refresh)
        )
        "MinePage" -> listOf(
            QuickAction(ActionSlot.Left, Icons.Default.Person, "返回首页", ActionKind.Home),
            QuickAction(ActionSlot.Center, Icons.Default.Refresh, "刷新", ActionKind.Refresh)
        )
        else -> emptyList()
    }
}

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

    // ==== 当前路由的快捷操作配置 ====
    val isDarkMode by GlobalData.isDarkMode.collectAsState()
    val quickActions = remember(currentRoute, isDarkMode) { getQuickActions(currentRoute, isDarkMode) }

    // ==== 手势 / 动画状态 ====
    var showActionSheet by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var activeSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var executedSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var lastVibratedSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var pressedItemIndex by remember { mutableStateOf(1) }

    val rotationAnim = remember { Animatable(0f) }
    val expansionAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current

    // 目标位置
    val baseTargetYPx = with(density) { (-100).dp.toPx() }
    val minDragYPx = with(density) { -30.dp.toPx() }
    val snapRadiusPx = with(density) { 60.dp.toPx() }

    // 每个 slot 的目标坐标 (px)
    val slotTargetX = remember(density) {
        ActionSlot.entries.associateWith { with(density) { it.labelX.dp.toPx() } }
    }
    val slotTargetY = remember(density) {
        ActionSlot.entries.associateWith { baseTargetYPx + with(density) { it.labelYOffset.dp.toPx() } }
    }

    // overlay 原点偏移
    val screenWidthDp = configuration.screenWidthDp.dp
    val tabWidthDp = screenWidthDp / 3
    val originXDp = when (pressedItemIndex) {
        0 -> -tabWidthDp
        2 -> tabWidthDp
        else -> 0.dp
    }

    // 展开和收回动作
    LaunchedEffect(showActionSheet) {
        if (showActionSheet) {
            expansionAnim.snapTo(0f)
            expansionAnim.animateTo(
                1f,
                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
            )
        } else {
            val stiffness = if (isExecuting) 100f else 280f
            expansionAnim.animateTo(
                0f,
                spring(dampingRatio = 1f, stiffness = stiffness)
            )
        }
    }

    // 方向切换时触发震动反馈
    LaunchedEffect(activeSlot) {
        if (activeSlot != null && activeSlot != lastVibratedSlot && !isExecuting) {
            HapticUtil.performTick(view)
            lastVibratedSlot = activeSlot
        } else if (activeSlot == null) {
            lastVibratedSlot = null
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

        // ================= 快捷操作浮层 =================
        AnimatedVisibility(
            visible = showActionSheet,
            enter = fadeIn(tween(150)) + scaleIn(
                initialScale = 0.6f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
            ),
            // 【核心修复2】强制延迟外层容器的消失时间 (delayMillis = 450)
            // 这给了内部图标充足的 450ms 时间，让它们不受外层影响、纯粹地靠物理弹簧往中间聚拢并缩放消失。解决了“原地消失”问题。
            exit = fadeOut(tween(durationMillis = 150, delayMillis = 450)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = originXDp, y = (-25).dp)
                .zIndex(10f)
        ) {
            val expansionProgress = expansionAnim.value

            // 手指光球的流体追踪 (带磁吸效果)
            val activeTargetX = activeSlot?.let { slotTargetX[it] }
            val activeTargetY = activeSlot?.let { slotTargetY[it] }
            val animFingerX by animateFloatAsState(
                targetValue = when {
                    !showActionSheet -> 0f
                    activeTargetX != null -> activeTargetX
                    else -> dragOffsetX
                },
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                label = "fingerX"
            )
            val animFingerY by animateFloatAsState(
                targetValue = when {
                    !showActionSheet -> 0f
                    activeTargetY != null -> activeTargetY
                    else -> dragOffsetY
                },
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                label = "fingerY"
            )

            Box(modifier = Modifier.fillMaxWidth()) {

                // --------- 为每个配置的 Action 渲染按钮 ---------
                for (action in quickActions) {
                    val slot = action.slot
                    val targetX = slotTargetX[slot]!!
                    val targetY = slotTargetY[slot]!!
                    val isThisActive = activeSlot == slot
                    val isThisExecuting = isExecuting && executedSlot == slot
                    val isHighlighted = isThisActive || isThisExecuting

                    val executeExitProgress = expansionProgress.coerceIn(0f, 1f)

                    val btnScale by animateFloatAsState(
                        targetValue = if (isHighlighted) 1.2f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                        label = "scale_${slot.name}"
                    )
                    val btnAlpha by animateFloatAsState(
                        targetValue = when {
                            isHighlighted -> 1f
                            activeSlot != null -> 0.35f
                            else -> 0.85f
                        },
                        label = "alpha_${slot.name}"
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset {
                                IntOffset(
                                    (targetX * expansionProgress).roundToInt(),
                                    (targetY * expansionProgress).roundToInt()
                                )
                            }
                            // 叠加绑定进度，让它们跟随路程同步绝对消散
                            .scale(btnScale * executeExitProgress)
                            .alpha(executeExitProgress)
                            .size(48.dp)
                            .shadow(
                                elevation = if (isHighlighted) 12.dp else 3.dp,
                                shape = CircleShape,
                                ambientColor = darkModeColor(YamiboColors.primary, YamiboColors.primaryDark),
                                spotColor = darkModeColor(YamiboColors.primary, YamiboColors.primaryDark)
                            )
                            .background(darkModeColor(
                                YamiboColors.onSurface.copy(alpha = 0.92f),
                                YamiboColors.onSurfaceDark.copy(alpha = 0.92f)
                            ), CircleShape)
                            .border(
                                width = 1.dp,
                                color = darkModeColor(
                                    YamiboColors.primary.copy(alpha = if (isHighlighted) 0.8f else 0.12f),
                                    YamiboColors.primaryDark.copy(alpha = if (isHighlighted) 0.8f else 0.12f)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.iconResId?.let { ImageVector.vectorResource(id = it) } ?: action.icon,
                            contentDescription = action.description,
                            tint = darkModeColor(
                                YamiboColors.primary.copy(alpha = btnAlpha),
                                YamiboColors.primaryDark.copy(alpha = btnAlpha)
                            ),
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(
                                    if (isThisExecuting && action.kind == ActionKind.Refresh) rotationAnim.value
                                    else 0f
                                )
                        )
                    }
                }

                // --------- 灵动追随光球 ---------
                if (!isExecuting) {
                    val executeExitProgress = expansionProgress.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset {
                                IntOffset(
                                    animFingerX.roundToInt(),
                                    animFingerY.roundToInt()
                                )
                            }
                            // 【核心修复3】让未选中状态下的中心光球也能跟随退回动画同步缩小消失，对齐逻辑
                            .scale(executeExitProgress)
                            .alpha(executeExitProgress)
                            .size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            darkModeColor(
                                                YamiboColors.primary.copy(alpha = 0.4f),
                                                YamiboColors.primaryDark.copy(alpha = 0.4f)
                                            ),
                                            Color.Transparent
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .shadow(6.dp, CircleShape, spotColor = darkModeColor(YamiboColors.primary, YamiboColors.primaryDark))
                                .background(darkModeColor(YamiboColors.primary, YamiboColors.primaryDark), CircleShape)
                        )
                    }
                }
            }
        }

        // ================= 底部导航栏 (始终贴底) =================
        NavigationBar(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .align(Alignment.BottomCenter),
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = darkModeColor(YamiboColors.onSurface, YamiboColors.onSurfaceDark)
        ) {
            uiState.icons.forEachIndexed { index, item ->
                val targetRoute = pageList[index]
                val isSelected = currentRoute == targetRoute
                NavigationBarItem(
                    icon = { Icon(item, contentDescription = "") },
                    selected = isSelected,
                    colors = NavigationBarItemDefaults.colors(indicatorColor = darkModeColor(YamiboColors.tertiary, YamiboColors.tertiaryDark)),
                    onClick = {
                        if (currentRoute == targetRoute) return@NavigationBarItem
                        navBarVM.changeSelection(index, navController)
                    },
                    modifier = Modifier.pointerInput(isSelected) {
                        if (!isSelected || targetRoute == "FavoritePage") return@pointerInput

                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (isExecuting) return@detectDragGesturesAfterLongPress
                                HapticUtil.performLongPress(view)
                                pressedItemIndex = index
                                showActionSheet = true
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                activeSlot = null
                                coroutineScope.launch { rotationAnim.snapTo(0f) }
                            },
                            onDrag = { change, dragAmount ->
                                if (isExecuting) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y

                                if (dragOffsetY > minDragYPx) {
                                    activeSlot = null
                                    return@detectDragGesturesAfterLongPress
                                }

                                val sortedSlots = quickActions.map { it.slot }
                                    .sortedBy { slotTargetX[it]!! }

                                activeSlot = null
                                for ((i, slot) in sortedSlots.withIndex()) {
                                    val tx = slotTargetX[slot]!!
                                    val ty = slotTargetY[slot]!!
                                    val leftBound = if (i > 0) {
                                        (tx + slotTargetX[sortedSlots[i - 1]]!!) / 2f
                                    } else Float.NEGATIVE_INFINITY
                                    val rightBound = if (i < sortedSlots.size - 1) {
                                        (tx + slotTargetX[sortedSlots[i + 1]]!!) / 2f
                                    } else Float.POSITIVE_INFINITY

                                    if (dragOffsetX in leftBound..rightBound) {
                                        val dist = hypot(dragOffsetX - tx, dragOffsetY - ty)
                                        if (dist < snapRadiusPx) {
                                            activeSlot = slot
                                        }
                                        break
                                    }
                                }
                            },
                            onDragEnd = {
                                if (isExecuting) return@detectDragGesturesAfterLongPress

                                val slot = activeSlot
                                if (slot != null) {
                                    val action = quickActions.first { it.slot == slot }
                                    isExecuting = true
                                    executedSlot = slot
                                    HapticUtil.performLongPress(view)

                                    when (action.kind) {
                                        ActionKind.Home -> {
                                            currentRoute?.let { navBarVM.triggerGoHome(it) }
                                            coroutineScope.launch {
                                                delay(150) // 黄金停顿：让球体完成放大弹簧动画
                                                showActionSheet = false // 触发回缩和柔和退场
                                                delay(450) // 等待回缩动画完全播完
                                                isExecuting = false
                                                executedSlot = null
                                                activeSlot = null
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            }
                                        }

                                        ActionKind.Refresh -> {
                                            currentRoute?.let { navBarVM.triggerRefresh(it) }
                                            coroutineScope.launch {
                                                val spinJob = launch {
                                                    rotationAnim.animateTo(
                                                        targetValue = rotationAnim.value + 360f,
                                                        animationSpec = tween(600, easing = LinearEasing)
                                                    )
                                                }
                                                delay(150) // 转动一小段圆弧，作为视觉确认
                                                showActionSheet = false

                                                delay(450)
                                                isExecuting = false
                                                executedSlot = null
                                                spinJob.cancel()
                                                rotationAnim.snapTo(0f)
                                                activeSlot = null
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            }
                                        }

                                        ActionKind.DarkMode -> {
                                            currentRoute?.let { navBarVM.triggerDarkMode(it) }
                                            coroutineScope.launch {
                                                delay(150)
                                                showActionSheet = false
                                                delay(450)
                                                isExecuting = false
                                                executedSlot = null
                                                activeSlot = null
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            }
                                        }
                                    }
                                } else {
                                    // 未选任何功能时的取消逻辑
                                    showActionSheet = false
                                    coroutineScope.launch {
                                        delay(400) // 等待图标优雅且同步地收缩完再归零状态
                                        activeSlot = null
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                    }
                                }
                            },
                            onDragCancel = {
                                if (isExecuting) return@detectDragGesturesAfterLongPress
                                showActionSheet = false
                                coroutineScope.launch {
                                    delay(400) // 同样等待退场播完再重置光球状态，防割裂
                                    activeSlot = null
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                }
                            }
                        )
                    }
                )
            }
        }

        // ================= 网页进度条 =================
        val isAtRoot = when (currentRoute) {
            "BBSPage" -> navBarVM.isBbsAtRoot
            "MinePage" -> navBarVM.isMineAtRoot
            else -> true
        }

        AnimatedVisibility(
            visible = webProgress > 0 && animatedProgress.value < 1f &&
                    (currentRoute == "BBSPage" || currentRoute == "MinePage") &&
                    !isAtRoot,
            enter = fadeIn(tween(200)) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(200)
            ),
            exit = fadeOut(tween(300)) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(300)
            ),
            modifier = Modifier.align(Alignment.TopCenter).zIndex(20f)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = darkModeColor(YamiboColors.primary, YamiboColors.primaryDark),
                trackColor = darkModeColor(
                    YamiboColors.primary.copy(alpha = 0.1f),
                    YamiboColors.primaryDark.copy(alpha = 0.1f)
                ),
                strokeCap = StrokeCap.Round
            )
        }
    }
}