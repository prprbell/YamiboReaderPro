package org.shirakawatyu.yamibo.novel.ui.page

import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.item.FavoriteItem
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.theme.YellowLightLight
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.NetworkOptimizationDialog
import org.shirakawatyu.yamibo.novel.ui.widget.TopBar
import org.shirakawatyu.yamibo.novel.util.AutoSignManager
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.manga.MangaProber
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
    val isFavoriteCollapsed by GlobalData.isFavoriteCollapsed.collectAsState()
    val isDnsOptimizationEnabled by GlobalData.isDnsOptimizationEnabled.collectAsState()
    val isClickToTopEnabled by GlobalData.isClickToTopEnabled.collectAsState()
    val selectedItems = uiState.selectedItems
    var cacheInfoMap = uiState.cacheInfoMap
    val currentHomePage by GlobalData.homePageRoute.collectAsState()
    var showCacheManagement by remember { mutableStateOf(false) }
    var showDataSaverDialog by remember { mutableStateOf(false) }
    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var showClickToTopDialog by remember { mutableStateOf(false) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    var isLoggedIn by remember {
        mutableStateOf(
            CookieManager.getInstance().getCookie("https://bbs.yamibo.com")
                ?.contains("EeqY_2132_auth=") == true
        )
    }

    LaunchedEffect(Unit) { favoriteVM.refreshCacheInfo() }
    DisposableEffect(Unit) {
        favoriteVM.isFavoritePageVisible = true
        onDispose {
            favoriteVM.isFavoritePageVisible = false
        }
    }
    var showBookmarkManagement by remember { mutableStateOf(false) }
    var showDirectoryManagement by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var directoryList by remember { mutableStateOf<List<MangaDirectory>>(emptyList()) }
    val context = LocalContext.current
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context as ComponentActivity)
    var probingUrl by remember { mutableStateOf<String?>(null) }
    var probingJob by remember { mutableStateOf<Job?>(null) }
    BackHandler(enabled = probingUrl != null) {
        probingJob?.cancel()
        probingJob = null
        probingUrl = null
    }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    var previousListSize by remember { mutableIntStateOf(favoriteList.size) }
    var wasAtTop by remember { mutableStateOf(true) }
    var showTopToast by remember { mutableStateOf(false) }
    var newItemsCount by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                favoriteVM.lastPauseTime = System.currentTimeMillis()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch(Dispatchers.IO) {
                    for (i in 0 until 5) {
                        val realCookie =
                            CookieManager.getInstance().getCookie("https://bbs.yamibo.com") ?: ""
                        val auth = realCookie.contains("EeqY_2132_auth=")
                        if (isLoggedIn != auth) {
                            withContext(Dispatchers.Main) { isLoggedIn = auth }
                        }
                        delay(1000)
                    }
                }
                val isQuickReturn = favoriteVM.lastPauseTime != 0L &&
                        (System.currentTimeMillis() - favoriteVM.lastPauseTime < 2400L)

                coroutineScope.launch {
                    if (pendingScrollToTop) {
                        lazyListState.scrollToItem(0)
                        pendingScrollToTop = false
                    }

                    delay(350)

                    if (!isQuickReturn) {
                        when (favoriteVM.getEffectiveResumeStrategy()) {
                            FavoriteVM.RefreshStrategy.SKIP -> {
                            }

                            FavoriteVM.RefreshStrategy.SMART -> {
                                favoriteVM.refreshList(showLoading = false, isSmartSync = true)
                            }

                            FavoriteVM.RefreshStrategy.FULL -> {
                                favoriteVM.refreshList(showLoading = false, isSmartSync = false)
                            }
                        }
                    }

                    favoriteVM.nextResumeStrategy = FavoriteVM.RefreshStrategy.FULL
                    favoriteVM.getCacheInfo { info -> cacheInfoMap = info }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    LaunchedEffect(lazyListState) {
        androidx.compose.runtime.snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            wasAtTop = (index == 0 && offset <= 50)
        }
    }

    var previousCategory by remember { mutableIntStateOf(favoriteVM.currentCategory) }
    var previousManageMode by remember { mutableStateOf(uiState.isInManageMode) }


    LaunchedEffect(favoriteList, favoriteVM.currentCategory, uiState.isInManageMode) {
        val addedCount = favoriteList.size - previousListSize

        val isSameCategory = favoriteVM.currentCategory == previousCategory
        val isSameManageMode = uiState.isInManageMode == previousManageMode
        val isNotInitialLoad = previousListSize > 0

        if (isSameCategory && isSameManageMode && isNotInitialLoad && addedCount > 0) {
            if (wasAtTop) {
                lazyListState.animateScrollToItem(0)
            } else {
                newItemsCount = addedCount
                showTopToast = true
            }
        }
        previousListSize = favoriteList.size
        previousCategory = favoriteVM.currentCategory
        previousManageMode = uiState.isInManageMode
    }
    LaunchedEffect(showTopToast) {
        if (showTopToast) {
            delay(2500)
            showTopToast = false
        }
    }
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

    var currentCategoryId by rememberSaveable { mutableIntStateOf(favoriteVM.currentCategory) }
    val currentCat = categoryOptions.find { it.first == currentCategoryId } ?: categoryOptions[0]

    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (navBarsPadding.value > lockedNavHeightValue) lockedNavHeightValue = navBarsPadding.value
    val lockedNavHeight = lockedNavHeightValue.dp

    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var lockedStatusHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (statusBarsPadding.value > lockedStatusHeightValue) lockedStatusHeightValue =
        statusBarsPadding.value
    val lockedStatusHeight = lockedStatusHeightValue.dp
    var showHomePageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        favoriteVM.refreshCacheInfo()
        SettingsUtil.getHomePage { GlobalData.homePageRoute.value = it }
    }
    Column(
        modifier = Modifier
            .padding(bottom = lockedNavHeight + 50.dp)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (lockedStatusHeight > 0.dp) lockedStatusHeight else 28.dp)
                .background(YamiboColors.onSurface)
        )
        TopBar(title = "") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左半部：标题或分类切换
                if (isInManageMode) {
                    Text(
                        text = "管理收藏 (${selectedItems.size})",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    var categoryMenuExpanded by remember { mutableStateOf(false) }
                    val arrowRotation by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (categoryMenuExpanded) 180f else 0f,
                        animationSpec = tween(
                            durationMillis = 250,
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
                                fontWeight = FontWeight.Medium,
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
                                        currentCategoryId = typeId
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

                // 右半部：操作菜单区域
                if (isInManageMode) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { favoriteVM.toggleManageMode() }) { Text("完成") }
                } else {
                    var menuExpanded by remember { mutableStateOf(false) }
                    var lastMenuClickTime by remember { mutableLongStateOf(0L) }
                    Box {
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
                                tint = YamiboColors.primary
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            offset = DpOffset(x = 9.dp, y = 16.dp),
                            modifier = Modifier
                                .width(256.dp)
                                .background(Color(0xFFFFFCF0))
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            // 第一排：设置首页 管理缓存
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text("设置首页") },
                                    onClick = { showHomePageDialog = true; menuExpanded = false },
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
                                    onClick = {
                                        favoriteVM.toggleManageMode(); menuExpanded = false
                                    },
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

                            // 第二排：折叠 管理书签
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text(if (isFavoriteCollapsed) "关闭折叠" else "折叠模式") },
                                    onClick = {
                                        menuExpanded = false
                                        coroutineScope.launch {
                                            delay(250)
                                            val newState = !isFavoriteCollapsed
                                            GlobalData.isFavoriteCollapsed.value = newState
                                            SettingsUtil.saveFavoriteCollapseMode(newState)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(id = if (isFavoriteCollapsed) R.drawable.ic_unfold_more else R.drawable.ic_unfold_less),
                                            null,
                                            Modifier.size(24.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text("管理缓存") },
                                    onClick = { showCacheManagement = true; menuExpanded = false },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.ic_download),
                                            null,
                                            Modifier.size(24.dp)
                                        )
                                    }
                                )
                            }

                            // 第三排：置顶 管理收藏
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text(if (isClickToTopEnabled) "关闭置顶" else "阅后置顶") },
                                    onClick = {
                                        menuExpanded = false
                                        if (isClickToTopEnabled) {
                                            coroutineScope.launch {
                                                delay(250)
                                                GlobalData.isClickToTopEnabled.value = false
                                                SettingsUtil.saveClickToTopMode(false)
                                            }
                                        } else showClickToTopDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_align_top),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = if (isClickToTopEnabled) YamiboColors.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text("管理书签") },
                                    onClick = {
                                        showBookmarkManagement = true; menuExpanded = false
                                    },
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
                                            color = if (isDnsOptimizationEnabled) YamiboColors.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showCustomDnsDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Build,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = if (isDnsOptimizationEnabled) YamiboColors.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text("管理目录") },
                                    onClick = {
                                        favoriteVM.getDirectoryList { dirs ->
                                            directoryList = dirs; showDirectoryManagement = true
                                        }
                                        menuExpanded = false
                                    },
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
                                    text = { Text(if (isAutoSignIn) "关闭签到" else "自动签到") },
                                    onClick = {
                                        menuExpanded = false
                                        coroutineScope.launch {
                                            delay(250)
                                            val newState = !isAutoSignIn
                                            GlobalData.isAutoSignInEnabled.value = newState
                                            SettingsUtil.saveAutoSignInMode(newState)

                                            if (newState) {
                                                AutoSignManager.checkAndSignIfNeeded(
                                                    context,
                                                    force = true
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isAutoSignIn) Icons.Default.Clear
                                            else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = if (isAutoSignIn) YamiboColors.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.weight(1f),
                                    text = { Text("刷新列表") },
                                    onClick = {
                                        favoriteVM.refreshList(
                                            showLoading = true,
                                            isSmartSync = false
                                        )
                                        menuExpanded = false
                                    },
                                    enabled = !isRefreshing,
                                    leadingIcon = {
                                        if (isRefreshing) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(
                                            androidx.compose.material.icons.Icons.Default.Refresh,
                                            null,
                                            Modifier.size(24.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {

            // 收藏列表
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(bottom = 40.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp, 3.dp)
            ) {
                itemsIndexed(
                    items = favoriteList,
                    key = { _, item -> item.url }
                ) { _, item ->
                    val currentOnClick = remember(item, isInManageMode, isClickToTopEnabled) {
                        {
                            if (isInManageMode) {
                                favoriteVM.toggleItemSelection(item.url)
                            } else {
                                if (isClickToTopEnabled) {
                                    val capturedWasAtTop = wasAtTop
                                    if (capturedWasAtTop) {
                                        pendingScrollToTop = true
                                    }
                                    coroutineScope.launch {
                                        delay(400)
                                        favoriteVM.moveToTop(item.url)
                                        if (capturedWasAtTop) {
                                            delay(50)
                                            lazyListState.scrollToItem(0)
                                            pendingScrollToTop = false
                                        }
                                    }
                                }
                                favoriteVM.updateStrategyBeforeNavigation(item.type)
                                val encodedUrl = java.net.URLEncoder.encode(item.url, "utf-8")
                                when (item.type) {
                                    0 -> navController.navigate("ProbingPage/$encodedUrl")
                                    1 -> navController.navigate("ReaderPage/$encodedUrl")
                                    3 -> navController.navigate("OtherWebPage/$encodedUrl")
                                    else -> {
                                        val targetUrl = item.lastMangaUrl ?: item.url
                                        val encodedTarget =
                                            java.net.URLEncoder.encode(targetUrl, "utf-8")
                                        val encodedOriginal =
                                            java.net.URLEncoder.encode(item.url, "utf-8")
                                        probingUrl = targetUrl
                                        probingJob = coroutineScope.launch {
                                            MangaProber().probeUrl(
                                                context = context,
                                                url = targetUrl,
                                                onSuccess = { urls, title, html ->
                                                    val normalizedUrls = urls
                                                        .map { it.trim() }
                                                        .filter { it.isNotBlank() }
                                                        .distinct()

                                                    val targetIndex = item.lastPage.coerceIn(
                                                        0,
                                                        maxOf(0, normalizedUrls.size - 1)
                                                    )

                                                    MangaImagePipeline.handoffPrefetch(
                                                        context = context.applicationContext,
                                                        urls = normalizedUrls,
                                                        clickedIndex = targetIndex
                                                    )

                                                    GlobalData.tempMangaUrls = normalizedUrls
                                                    GlobalData.tempHtml = html
                                                    GlobalData.tempTitle = title
                                                    GlobalData.tempMangaIndex = targetIndex

                                                    navController.navigate("NativeMangaPage?url=$encodedTarget&originalUrl=$encodedOriginal")

                                                    coroutineScope.launch {
                                                        delay(300)
                                                        probingUrl = null
                                                        probingJob = null
                                                    }
                                                },
                                                onFallback = {
                                                    navController.navigate("MangaWebPage/$encodedTarget/$encodedOriginal?fastForward=false&initialPage=${item.lastPage}")
                                                    probingUrl = null
                                                    probingJob = null
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                            onClick = currentOnClick,
                            modifier = Modifier
                                .animateItem()
                                .longPressDraggableHandle(
                                    enabled = !isInManageMode,
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                ),
                            isDragging = isDragging,
                            isManageMode = isInManageMode,
                            isSelected = isSelected,
                            isHidden = item.isHidden,
                            type = item.type,
                            cacheInfo = cacheInfoMap[item.url],
                            isGlobalCollapsed = isFavoriteCollapsed,
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
            // 悬浮删除操作栏
            androidx.compose.animation.AnimatedVisibility(
                visible = isInManageMode,
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it + 100 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ),
                exit = androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it + 100 },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = YellowLightLight,
                    shadowElevation = 3.dp,
                    border = BorderStroke(
                        width = 2.dp,
                        color = YamiboColors.primary.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { favoriteVM.hideSelectedItems() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_visibility_off),
                                "隐藏",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("隐藏")
                        }

                        TextButton(
                            onClick = { favoriteVM.unhideSelectedItems() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_visibility),
                                "显示",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("显示")
                        }

                        Spacer(Modifier.width(4.dp))

                        TextButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) showDeleteConfirmDialog = true
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("删除")
                        }
                    }
                }
            }

            // 悬浮气泡
            androidx.compose.animation.AnimatedVisibility(
                visible = showTopToast,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) +
                        androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier.clickable {
                        showTopToast = false
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(0)
                        }
                    }
                ) {
                    Text(
                        text = "发现了 $newItemsCount 条新收藏 (点击查看)",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 首页设置对话框
        if (showHomePageDialog) {
            var tempHomePage by remember { mutableStateOf(currentHomePage) }

            AlertDialog(
                onDismissRequest = { showHomePageDialog = false },
                title = {
                    Text("设置首页", color = MaterialTheme.colorScheme.primary)
                },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 收藏页选项
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { tempHomePage = "FavoritePage" }
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "收藏页图标",
                                modifier = Modifier.size(29.dp),
                                tint = if (tempHomePage == "FavoritePage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant // 使用 tempHomePage
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = tempHomePage == "FavoritePage",
                                    onClick = { tempHomePage = "FavoritePage" },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "收藏页",
                                    color = if (tempHomePage == "FavoritePage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface // 使用 tempHomePage
                                )
                            }
                        }

                        // 论坛页选项
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { tempHomePage = "BBSPage" }
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "论坛页图标",
                                modifier = Modifier.size(29.dp),
                                tint = if (tempHomePage == "BBSPage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant // 使用 tempHomePage
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = tempHomePage == "BBSPage",
                                    onClick = { tempHomePage = "BBSPage" },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "论坛页",
                                    color = if (tempHomePage == "BBSPage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface // 使用 tempHomePage
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        GlobalData.homePageRoute.value = tempHomePage
                        SettingsUtil.saveHomePage(tempHomePage)
                        showHomePageDialog = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showHomePageDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
        if (showClickToTopDialog) {
            AlertDialog(
                onDismissRequest = { showClickToTopDialog = false },
                title = {
                    Text("开启阅后置顶", color = MaterialTheme.colorScheme.primary)
                },
                text = {
                    Text("开启后，点击进入的收藏项将自动排序至列表首位。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        GlobalData.isClickToTopEnabled.value = true
                        SettingsUtil.saveClickToTopMode(true)
                        showClickToTopDialog = false
                    }) {
                        Text("确认开启")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClickToTopDialog = false }) {
                        Text("取消")
                    }
                }
            )
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
        // DNS确认对话框
        if (showCustomDnsDialog) {
            NetworkOptimizationDialog(onDismiss = { showCustomDnsDialog = false })
        }
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("删除收藏", color = MaterialTheme.colorScheme.error) },
                text = { Text("确定要删除这 ${selectedItems.size} 项收藏吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            favoriteVM.deleteSelectedFavorites { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("确认删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
                }
            )
        }
    }
    // 监听探测状态，隐藏底部导航栏，让黑屏真正全屏
    LaunchedEffect(probingUrl) {
        if (probingUrl != null) {
            bottomNavBarVM.setBottomNavBarVisibility(false)
        } else {
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

    // 完美复刻的黑屏加载动画
    androidx.compose.animation.AnimatedVisibility(
        visible = probingUrl != null,
        enter = androidx.compose.animation.fadeIn(tween(0)), // 瞬间变黑
        exit = androidx.compose.animation.fadeOut(tween(150)),
        modifier = Modifier.zIndex(100f) // 确保在最顶层
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 拦截一切手势，防止用户在黑屏期间乱点
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
@Composable
fun CacheManagementDialog(
    favoriteList: List<Favorite>,
    cacheInfoMap: Map<String, FavoriteVM.CacheInfo>,
    onDismiss: () -> Unit,
    onDeleteCache: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 用于图片缓存管理的上下文和协程
    val context = LocalContext.current

    val coroutineScope = rememberCoroutineScope()
    var imageCacheSize by remember { mutableLongStateOf(0L) }
    val isAutoClearCache by GlobalData.isAutoClearCacheEnabled.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            imageCacheSize = context.imageLoader.diskCache?.size ?: 0L
        }
    }

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
                // 图片缓存管理区块
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 16.dp, bottom = 12.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "图片缓存 (漫画)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "占用空间: ${formatFileSize(imageCacheSize)}",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                        alpha = 0.8f
                                    )
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        context.imageLoader.diskCache?.clear()
                                        context.imageLoader.memoryCache?.clear()
                                        imageCacheSize = context.imageLoader.diskCache?.size ?: 0L
                                    }
                                },
                                enabled = imageCacheSize > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "清理图片",
                                    tint = if (imageCacheSize > 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newState = !isAutoClearCache
                                    GlobalData.isAutoClearCacheEnabled.value = newState
                                    SettingsUtil.saveAutoClearCacheMode(newState)
                                }
                                .padding(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "自动清空图片缓存",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Switch(
                                checked = isAutoClearCache,
                                onCheckedChange = { newState ->
                                    GlobalData.isAutoClearCacheEnabled.value = newState
                                    SettingsUtil.saveAutoClearCacheMode(newState)
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

                // 文本统计信息
                // val totalCached = cacheInfoMap.values.sumOf { it.totalPages }
                val totalSize = cacheInfoMap.values.sumOf { it.totalSize }

                if (cacheInfoMap.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "文本缓存: ${cacheInfoMap.size} 部，占用空间: ${
                                    formatFileSize(
                                        totalSize
                                    )
                                }", fontSize = 12.sp
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
                            val cleanTitle = favorite.title.replace(
                                Regex("^(?:【.*?】|\\[.*?\\]|[\\s\\u00A0\\u3000])+"),
                                ""
                            ).ifBlank { favorite.title }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        cleanTitle,
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
                                val displayTitle = info.title?.replace(
                                    Regex("^(?:【.*?】|\\[.*?\\]|[\\s\\u00A0\\u3000])+"),
                                    ""
                                )?.ifBlank { info.title } ?: info.url
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            displayTitle,
                                            fontSize = if (info.title != null) 14.sp else 12.sp,
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
                    // 无文本缓存提示
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
                            "暂无文本缓存",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Text("清空所有文本缓存")
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
            text = { Text("确定要清空所有文本缓存吗？此操作不可撤销。") },
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
                            val cleanTitle = favorite.title.replace(
                                Regex("^(?:【.*?】|\\[.*?\\]|[\\s\\u00A0\\u3000])+"),
                                ""
                            ).ifBlank { favorite.title }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        cleanTitle,
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
}

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