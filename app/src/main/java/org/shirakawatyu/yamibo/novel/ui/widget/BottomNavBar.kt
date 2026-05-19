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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.util.HapticUtil
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 操作槽位 — 决定按钮在导航栏上方的水平位置与垂直偏移 */
enum class ActionSlot(val labelX: Float, val labelYOffset: Float) {
    Left(-85f, 15f),
    Center(0f, -20f),
    Right(85f, 15f)
}

/** 操作类型 — 决定松手后的执行逻辑 */
enum class ActionKind { Home, Refresh, DarkMode, CheckUpdate }

/** 单个快捷操作的配置 */
data class QuickAction(
    val slot: ActionSlot,
    val icon: ImageVector,
    val description: String,
    val kind: ActionKind,
    val iconResId: Int? = null   // 非 null 时优先使用此资源，支持夜间模式动态切换图标
)

// ================= 新增二级菜单的配置 =================
enum class SubActionSlot(val labelX: Float, val labelYOffset: Float) {
    FarLeft(-105f, -82f),
    NearLeft(-42f, -108f),
    NearRight(42f, -108f),
    FarRight(105f, -82f)
}

data class ThemeQuickAction(
    val slot: SubActionSlot,
    val name: String,
    val themeId: Int,
    val color: Color,
    val hint: String
)

val darkThemeActions = listOf(
    ThemeQuickAction(SubActionSlot.FarLeft, "暗黑", 0, Color(0xFF121212), "暗"),
    ThemeQuickAction(SubActionSlot.NearLeft, "灰蓝", 1, Color(0xFF13191F), "蓝"),
    ThemeQuickAction(SubActionSlot.NearRight, "纯黑", 2, Color(0xFF000000), "黑"),
    ThemeQuickAction(SubActionSlot.FarRight, "紫夜", 3, Color(0xFF15151F), "紫")
)

val lightThemeActions = listOf(
    // FarLeft  原色 (-1)：不注入任何主题，呈现 yamibo 论坛原生暖米黄
    ThemeQuickAction(SubActionSlot.FarLeft, "原色", -1, Color(0xFFC9B388), "原"),
    // NearLeft 纯白 (11)：现代极简白色主题
    ThemeQuickAction(SubActionSlot.NearLeft, "纯白", 11, Color.White, "白"),
    // NearRight 论坛 (12)：COBALT_FORUM 蓝 — 用论坛原生 #2B7ACD 主色
    ThemeQuickAction(SubActionSlot.NearRight, "论坛", 12, Color(0xFF2B7ACD), "蓝"),
    // FarRight 苔色 (13)：SAGE_GARDEN 绿 — 用森林绿 #4F7857 主色
    ThemeQuickAction(SubActionSlot.FarRight, "苔色", 13, Color(0xFF4F7857), "苔")
)

fun getThemeActions(isDarkMode: Boolean) = if (isDarkMode) darkThemeActions else lightThemeActions

/** 根据当前基础激活页面和用户长按触摸的图标，动态返回对应的快捷操作列表 */
private fun getQuickActions(baseActiveRoute: String?, touchedRoute: String, isDarkMode: Boolean): List<QuickAction> {
    return when (touchedRoute) {
        "BBSPage" -> {
            if (baseActiveRoute == "BBSPage") {
                listOf(
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
            } else {
                // 全局跨页触发：只要在非 BBS 页面时长按 BBS 图标，仅保留中央的夜间模式切换功能球
                listOf(
                    QuickAction(
                        ActionSlot.Center,
                        Icons.Default.Settings,
                        "夜间模式",
                        ActionKind.DarkMode,
                        iconResId = if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon
                    )
                )
            }
        }
        "MinePage" -> {
            if (baseActiveRoute == "MinePage") {
                listOf(
                    QuickAction(ActionSlot.Left, Icons.Default.Person, "返回首页", ActionKind.Home),
                    QuickAction(ActionSlot.Center, Icons.Default.Refresh, "刷新", ActionKind.Refresh)
                )
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }
}

@Composable
fun BottomNavBar(
    navController: NavController,
    currentRoute: String?,
    navBarVM: BottomNavBarVM,
    onQuickActionSheetVisibleChange: (Boolean) -> Unit = {}
) {
    if (!navBarVM.showBottomNavBar) return
    val uiState by navBarVM.uiState.collectAsState()
    val webProgress by GlobalData.webProgress.collectAsState()
    val pageList = listOf("FavoritePage", "BBSPage", "MinePage")

    val animatedProgress = remember { Animatable(0f) }
    val isDarkMode by GlobalData.isDarkMode.collectAsState()

    // 1. 规范化路由：将历史帖子详情强制映射回 MinePage，恢复底栏图标高亮与事件焦点
    val baseRoute = if (currentRoute?.startsWith("MineHistoryPostPage") == true) "MinePage" else currentRoute

    // 2. 将快捷操作声明为可变状态，在手指按下时根据触摸的具体图标进行动态计算和填充
    var activeQuickActions by remember { mutableStateOf<List<QuickAction>>(emptyList()) }

    // ==== 手势 / 动画状态 ====
    var showActionSheet by remember { mutableStateOf(false) }
    var inSubMenuMode by remember { mutableStateOf(false) }

    LaunchedEffect(showActionSheet) {
        onQuickActionSheetVisibleChange(showActionSheet)
    }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var activeSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var activeSubSlot by remember { mutableStateOf<SubActionSlot?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var executedSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var lastVibratedSlot by remember { mutableStateOf<Any?>(null) }
    var pressedItemIndex by remember { mutableStateOf(1) }

    val rotationAnim = remember { Animatable(0f) }
    val expansionAnim = remember { Animatable(0f) }
    val subMenuExpansionAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var pendingResetJob by remember { mutableStateOf<Job?>(null) }

    fun cancelPendingReset() {
        pendingResetJob?.cancel()
        pendingResetJob = null
    }

    fun resetGestureState() {
        activeSlot = null
        activeSubSlot = null
        inSubMenuMode = false
        dragOffsetX = 0f
        dragOffsetY = 0f
        lastVibratedSlot = null
    }

    fun scheduleGestureReset(delayMillis: Long) {
        cancelPendingReset()
        pendingResetJob = coroutineScope.launch {
            delay(delayMillis)
            if (!showActionSheet && !isExecuting) {
                resetGestureState()
            }
            pendingResetJob = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cancelPendingReset()
            onQuickActionSheetVisibleChange(false)
        }
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current

    val baseTargetYPx = with(density) { (-100).dp.toPx() }
    val minDragYPx = with(density) { -30.dp.toPx() }
    val snapRadiusPx = with(density) { 60.dp.toPx() }

    val slotTargetX = remember(density) { ActionSlot.entries.associateWith { with(density) { it.labelX.dp.toPx() } } }
    val slotTargetY = remember(density) { ActionSlot.entries.associateWith { baseTargetYPx + with(density) { it.labelYOffset.dp.toPx() } } }

    val subSlotTargetX = remember(density) { SubActionSlot.entries.associateWith { with(density) { it.labelX.dp.toPx() } } }
    val subSlotTargetY = remember(density) { SubActionSlot.entries.associateWith { baseTargetYPx + with(density) { it.labelYOffset.dp.toPx() } } }

    val screenWidthDp = configuration.screenWidthDp.dp
    val tabWidthDp = screenWidthDp / 3
    val originXDp = when (pressedItemIndex) {
        0 -> -tabWidthDp
        2 -> tabWidthDp
        else -> 0.dp
    }

    LaunchedEffect(showActionSheet) {
        if (showActionSheet) {
            expansionAnim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        } else {
            inSubMenuMode = false
            expansionAnim.animateTo(0f, spring(dampingRatio = 1f, stiffness = if (isExecuting) 100f else 350f))
        }
    }

    LaunchedEffect(inSubMenuMode) {
        if (inSubMenuMode) {
            subMenuExpansionAnim.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium))
        } else {
            subMenuExpansionAnim.animateTo(0f, tween(150))
        }
    }

    LaunchedEffect(activeSlot, activeSubSlot) {
        val currentActive = if (inSubMenuMode) activeSubSlot else activeSlot
        if (currentActive != null && currentActive != lastVibratedSlot && !isExecuting) {
            HapticUtil.performTick(view)
            lastVibratedSlot = currentActive
        } else if (currentActive == null) {
            lastVibratedSlot = null
        }
    }

    LaunchedEffect(webProgress) {
        val target = webProgress.toFloat() / 100f
        if (target < animatedProgress.value || target == 0f) animatedProgress.snapTo(target)
        else animatedProgress.animateTo(target, tween(durationMillis = 250, easing = LinearEasing))
    }

    val navBarHeight = 50.dp
    val quickActionLayerHeight = 240.dp

    Box(modifier = Modifier.fillMaxWidth().height(quickActionLayerHeight)) {

        // ================= 快捷操作浮层 =================
        AnimatedVisibility(
            visible = showActionSheet,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(durationMillis = 180, delayMillis = 400)),
            modifier = Modifier.align(Alignment.BottomCenter).offset(x = originXDp, y = (-25).dp).fillMaxWidth().height(quickActionLayerHeight).zIndex(10f)
        ) {
            val expansionProgress = expansionAnim.value.coerceIn(0f, 1.1f)

            val animFingerX by animateFloatAsState(
                targetValue = when {
                    !showActionSheet -> 0f
                    activeSubSlot != null -> subSlotTargetX[activeSubSlot]!!
                    activeSlot != null -> slotTargetX[activeSlot]!!
                    else -> dragOffsetX
                },
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
                label = "fingerX"
            )
            val animFingerY by animateFloatAsState(
                targetValue = when {
                    !showActionSheet -> 0f
                    activeSubSlot != null -> subSlotTargetY[activeSubSlot]!!
                    activeSlot != null -> slotTargetY[activeSlot]!!
                    else -> dragOffsetY
                },
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
                label = "fingerY"
            )

            Box(modifier = Modifier.fillMaxWidth().height(quickActionLayerHeight)) {
                // 1. 动态渲染气泡按钮
                for (action in activeQuickActions) {
                    val slot = action.slot
                    val targetX = slotTargetX[slot]!!
                    val targetY = slotTargetY[slot]!!
                    val isThisActive = activeSlot == slot
                    val isThisExecuting = isExecuting && executedSlot == slot
                    val isHighlighted = isThisActive || isThisExecuting

                    val appearAlpha = ((expansionProgress - 0.05f) / 0.4f).coerceIn(0f, 1f)

                    val btnAlpha by animateFloatAsState(
                        targetValue = when {
                            inSubMenuMode && slot == ActionSlot.Center -> 1f
                            inSubMenuMode && slot != ActionSlot.Center -> 0.25f
                            isHighlighted -> 1f
                            activeSlot != null -> 0.35f
                            else -> 0.85f
                        },
                        animationSpec = tween(durationMillis = 150)
                    )

                    val iconScale by animateFloatAsState(
                        targetValue = if (isHighlighted) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset { IntOffset((targetX * expansionProgress).roundToInt(), (targetY * expansionProgress).roundToInt()) }
                            .alpha(appearAlpha)
                            .size(68.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .shadow(if (isHighlighted) 12.dp else 5.dp, CircleShape, spotColor = darkThemeColor(YamiboColors.primary) { primary })
                                .background(darkThemeColor(YamiboColors.onSurface.copy(alpha = 0.94f)) { navBar.copy(alpha = 0.94f) }, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = action.iconResId?.let { ImageVector.vectorResource(id = it) } ?: action.icon,
                                contentDescription = action.description,
                                tint = darkThemeColor(YamiboColors.primary.copy(alpha = btnAlpha)) { primary.copy(alpha = btnAlpha) },
                                modifier = Modifier.scale(iconScale).size(24.dp).rotate(if (isThisExecuting && action.kind == ActionKind.Refresh) rotationAnim.value else 0f)
                            )
                        }
                    }
                }

                // 2. 渲染二级主题选色球
                if (inSubMenuMode || subMenuExpansionAnim.value > 0.01f) {
                    val centerTx = slotTargetX[ActionSlot.Center]!!
                    val centerTy = slotTargetY[ActionSlot.Center]!!
                    val subProgress = subMenuExpansionAnim.value

                    for (subAction in getThemeActions(isDarkMode)) {
                        val slot = subAction.slot
                        val isThisSubActive = activeSubSlot == slot
                        val subAppearAlpha = subProgress.coerceIn(0f, 1f)

                        val currentTx = centerTx + (subSlotTargetX[slot]!! - centerTx) * subProgress
                        val currentTy = centerTy + (subSlotTargetY[slot]!! - centerTy) * subProgress

                        val subBtnAlpha by animateFloatAsState(
                            targetValue = when {
                                isThisSubActive -> 1f
                                activeSubSlot != null -> 0.35f
                                else -> 0.85f
                            },
                            animationSpec = tween(150)
                        )
                        val subScale by animateFloatAsState(targetValue = if (isThisSubActive) 1.15f else 1f)

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset { IntOffset(currentTx.roundToInt(), currentTy.roundToInt()) }
                                .alpha(subAppearAlpha)
                                .size(68.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .scale(subScale)
                                    .shadow(if (isThisSubActive) 10.dp else 4.dp, CircleShape, spotColor = subAction.color)
                                    .background(subAction.color, CircleShape)
                                    .border(1.5.dp, darkThemeColor(YamiboColors.primary.copy(alpha = 0.25f)) { primary.copy(alpha = 0.25f) }, CircleShape)
                            ) {
                                androidx.compose.material3.Text(
                                    text = subAction.hint,
                                    color = if (subAction.themeId == -1 || subAction.themeId == 11) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f),
                                    fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // 3. 灵动追随手势的光球
                if (!isExecuting) {
                    val fingerAlpha = ((expansionProgress - 0.1f) / 0.3f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset { IntOffset(animFingerX.roundToInt(), animFingerY.roundToInt()) }
                            .alpha(fingerAlpha)
                            .size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(darkThemeColor(YamiboColors.primary.copy(alpha = 0.4f)) { primary.copy(alpha = 0.4f) }, Color.Transparent)), CircleShape))
                        Box(modifier = Modifier.size(12.dp).shadow(6.dp, CircleShape, spotColor = darkThemeColor(YamiboColors.primary) { primary }).background(darkThemeColor(YamiboColors.primary) { primary }, CircleShape))
                    }
                }
            }
        }

        // ================= 底部导航栏及触摸检测 =================
        NavigationBar(
            Modifier.fillMaxWidth().height(navBarHeight).align(Alignment.BottomCenter).zIndex(5f)
                .pointerInput(baseRoute, isDarkMode) {
                    var isNavBarLongPressAccepted = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            if (isExecuting) return@detectDragGesturesAfterLongPress
                            val touchedIndex = (startOffset.x / (size.width / pageList.size.toFloat())).toInt().coerceIn(0, pageList.lastIndex)
                            val targetRoute = pageList[touchedIndex]

                            // 【核心改动】：按下时动态根据当前 baseRoute 与触摸的图标 targetRoute 共同计算可用动作
                            val actionsForTouch = getQuickActions(baseRoute, targetRoute, isDarkMode)

                            isNavBarLongPressAccepted = actionsForTouch.isNotEmpty() && targetRoute != "FavoritePage"
                            if (!isNavBarLongPressAccepted) return@detectDragGesturesAfterLongPress

                            activeQuickActions = actionsForTouch // 动态绑定当前动作集
                            cancelPendingReset()
                            HapticUtil.performLongPress(view)
                            pressedItemIndex = touchedIndex
                            resetGestureState()
                            showActionSheet = true
                            coroutineScope.launch { rotationAnim.snapTo(0f) }
                        },
                        onDrag = { change, dragAmount ->
                            if (!isNavBarLongPressAccepted || isExecuting) return@detectDragGesturesAfterLongPress
                            change.consume()
                            dragOffsetX += dragAmount.x
                            dragOffsetY += dragAmount.y

                            if (inSubMenuMode) {
                                if (dragOffsetY > slotTargetY[ActionSlot.Center]!! + 30f) {
                                    inSubMenuMode = false
                                    activeSubSlot = null
                                    HapticUtil.performTick(view)
                                } else {
                                    activeSubSlot = null
                                    for (slot in SubActionSlot.entries) {
                                        val dist = hypot(dragOffsetX - subSlotTargetX[slot]!!, dragOffsetY - subSlotTargetY[slot]!!)
                                        if (dist < snapRadiusPx) {
                                            activeSubSlot = slot
                                            break
                                        }
                                    }
                                }
                            } else {
                                if (dragOffsetY > minDragYPx) {
                                    activeSlot = null
                                    return@detectDragGesturesAfterLongPress
                                }
                                activeSlot = null
                                for (slot in ActionSlot.entries) {
                                    val dist = hypot(dragOffsetX - slotTargetX[slot]!!, dragOffsetY - slotTargetY[slot]!!)
                                    if (dist < snapRadiusPx) {
                                        activeSlot = slot
                                        if (slot == ActionSlot.Center && dragOffsetY < slotTargetY[ActionSlot.Center]!! - 15f && activeQuickActions.any { it.slot == ActionSlot.Center && it.kind == ActionKind.DarkMode }) {
                                            inSubMenuMode = true
                                            HapticUtil.performTick(view)
                                        }
                                        break
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (!isNavBarLongPressAccepted || isExecuting) return@detectDragGesturesAfterLongPress
                            isNavBarLongPressAccepted = false

                            if (inSubMenuMode && activeSubSlot != null) {
                                val selectedThemeAction = getThemeActions(isDarkMode).first { it.slot == activeSubSlot }
                                isExecuting = true
                                HapticUtil.performLongPress(view)

                                baseRoute?.let { navBarVM.applyTheme(it, selectedThemeAction.themeId) }

                                coroutineScope.launch {
                                    delay(150)
                                    showActionSheet = false
                                    delay(450)
                                    isExecuting = false
                                    resetGestureState()
                                }
                            } else if (inSubMenuMode) {
                                isExecuting = true
                                HapticUtil.performLongPress(view)

                                if (isDarkMode) {
                                    // 暗色子菜单直接松手 -> 回到之前选的日间主题
                                    val prevLightId = GlobalData.lightModeTheme.value
                                    baseRoute?.let { navBarVM.applyTheme(it, if (prevLightId > 0) prevLightId + 10 else -1) }
                                } else {
                                    // 亮色子菜单直接松手 -> 切到暗色模式
                                    baseRoute?.let { navBarVM.applyTheme(it, GlobalData.darkModeTheme.value) }
                                }

                                coroutineScope.launch {
                                    delay(150)
                                    showActionSheet = false
                                    delay(450)
                                    isExecuting = false
                                    resetGestureState()
                                }
                            } else if (!inSubMenuMode && activeSlot != null) {
                                val slot = activeSlot!!
                                val action = activeQuickActions.first { it.slot == slot }

                                if (action.kind != ActionKind.DarkMode || !isDarkMode) {
                                    isExecuting = true
                                    executedSlot = slot
                                    HapticUtil.performLongPress(view)

                                    when (action.kind) {
                                        ActionKind.Home -> {
                                            baseRoute?.let { navBarVM.triggerGoHome(it) }
                                            coroutineScope.launch { delay(150); showActionSheet = false; delay(450); isExecuting = false; resetGestureState() }
                                        }
                                        ActionKind.Refresh -> {
                                            baseRoute?.let { navBarVM.triggerRefresh(it) }
                                            coroutineScope.launch {
                                                val spinJob = launch { rotationAnim.animateTo(rotationAnim.value + 360f, tween(600, easing = LinearEasing)) }
                                                delay(150); showActionSheet = false; delay(450); isExecuting = false; spinJob.cancel(); rotationAnim.snapTo(0f); resetGestureState()
                                            }
                                        }
                                        ActionKind.DarkMode -> {
                                            baseRoute?.let { navBarVM.applyTheme(it, GlobalData.darkModeTheme.value) }
                                            coroutineScope.launch { delay(150); showActionSheet = false; delay(450); isExecuting = false; resetGestureState() }
                                        }
                                        ActionKind.CheckUpdate -> {
                                            navBarVM.triggerCheckUpdate()
                                            coroutineScope.launch { delay(150); showActionSheet = false; delay(450); isExecuting = false; resetGestureState() }
                                        }
                                    }
                                } else {
                                    // 夜间模式下直接松手 -> 回到之前选的日间主题
                                    isExecuting = true
                                    executedSlot = slot
                                    HapticUtil.performLongPress(view)
                                    val prevLightId = GlobalData.lightModeTheme.value
                                    baseRoute?.let { navBarVM.applyTheme(it, if (prevLightId > 0) prevLightId + 10 else -1) }
                                    coroutineScope.launch { delay(150); showActionSheet = false; delay(450); isExecuting = false; resetGestureState() }
                                }
                            } else {
                                showActionSheet = false
                                scheduleGestureReset(400)
                            }
                        },
                        onDragCancel = {
                            if (!isNavBarLongPressAccepted || isExecuting) return@detectDragGesturesAfterLongPress
                            isNavBarLongPressAccepted = false
                            showActionSheet = false
                            scheduleGestureReset(400)
                        }
                    )
                },
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = darkThemeColor(YamiboColors.onSurface) { statusBar }
        ) {
            uiState.icons.forEachIndexed { index, item ->
                val targetRoute = pageList[index]
                NavigationBarItem(
                    icon = { Icon(item, contentDescription = "") },
                    selected = baseRoute == targetRoute, // 使用归一化的 baseRoute 判断高亮状态
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = darkThemeColor(MaterialTheme.colorScheme.onSurfaceVariant) { onPrimary.copy(alpha = 0.5f) },
                        indicatorColor = darkThemeColor(YamiboColors.tertiary) { tertiary }
                    ),
                    onClick = { if (baseRoute != targetRoute) navBarVM.changeSelection(index, navController) }
                )
            }
        }

        // ================= 网页进度条 =================
        AnimatedVisibility(
            visible = webProgress > 0 && animatedProgress.value < 1f &&
                    (baseRoute == "BBSPage" || baseRoute == "MinePage") &&
                    !navBarVM.isNavigating,
            enter = fadeIn(tween(200)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(200)),
            exit = fadeOut(tween(300)) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(300)),

            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -navBarHeight).zIndex(20f)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = darkThemeColor(YamiboColors.primary) { primary },
                trackColor = darkThemeColor(YamiboColors.primary.copy(alpha = 0.1f)) { primary.copy(alpha = 0.1f) },
                strokeCap = StrokeCap.Round
            )
        }
    }
}