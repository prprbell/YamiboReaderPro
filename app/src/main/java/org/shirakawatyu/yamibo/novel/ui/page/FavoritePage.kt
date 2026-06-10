package org.shirakawatyu.yamibo.novel.ui.page

import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckStrategy
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.item.FavoriteItem
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.theme.YellowLightLight
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.NetworkOptimizationDialog
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import org.shirakawatyu.yamibo.novel.ui.widget.TopBar
import org.shirakawatyu.yamibo.novel.util.AutoSignManager
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.manga.MangaProber
import org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.shirakawatyu.yamibo.novel.util.updateCheck.AutoUpdateCheckScheduler

// 非搜索场景下，确认"暂无收藏"前的等待时间。
// 调大以覆盖冷启动时 DataStore/Flow 首次发射前的空窗，避免闪现"暂无收藏"。
private const val EMPTY_STATE_CONFIRM_DELAY_MS = 1500L

/** 把"小时"格式化为更易读的文案：24 的整数倍显示为"天"，否则显示"小时"。 */
private fun formatCheckInterval(hours: Int): String =
    if (hours >= 24 && hours % 24 == 0) "${hours / 24} 天" else "$hours 小时"

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
    val probingTypeUrls = uiState.probingTypeUrls
    val updateCheckNovels = uiState.updateCheckNovels
    val updateCheckMangas = uiState.updateCheckMangas
    val novelCheckMap = remember(updateCheckNovels) { updateCheckNovels.associateBy { it.url } }
    val mangaCheckMap = remember(updateCheckMangas) { updateCheckMangas.associateBy { it.url } }
    val autoEnabledCount = remember(updateCheckNovels, updateCheckMangas) {
        updateCheckNovels.count { it.autoCheckEnabled } +
                updateCheckMangas.count { it.autoCheckEnabled }
    }
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
    var mangaUpdateCheckTarget by remember { mutableStateOf<Favorite?>(null) }
    var showMangaConfigDialog by remember { mutableStateOf(false) }
    var mangaConfigPresetStrategy by remember { mutableStateOf(MangaUpdateCheckStrategy.TAG) }
    var mangaConfigPresetKeyword by remember { mutableStateOf("") }
    var mangaConfigPresetBookName by remember { mutableStateOf("") }
    var mangaConfigTagAvailable by remember { mutableStateOf(false) }
    var novelUpdateCheckTarget by remember { mutableStateOf<Favorite?>(null) }
    var showNovelConfigDialog by remember { mutableStateOf(false) }
    var novelConfigAutoCheck by remember { mutableStateOf(false) }
    var novelConfigInterval by remember { mutableIntStateOf(6) }
    val openMangaConfig: (Favorite) -> Unit = { fav ->
        mangaUpdateCheckTarget = fav
        favoriteVM.getDirectoryList { dirs ->
            val t = Regex("tid=(\\d+)").find(fav.url)?.groupValues?.get(1)
            val existing = mangaCheckMap[fav.url]
            val matchedDir = if (existing != null) {
                dirs.find { it.cleanBookName == existing.cleanBookName }
                    ?: dirs.find { dir -> dir.chapters.any { it.tid == t } }
            } else {
                dirs.find { dir -> dir.chapters.any { it.tid == t } }
            }

            val tagAvailable =
                matchedDir?.strategy == org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy.TAG
            mangaConfigTagAvailable = tagAvailable

            mangaConfigPresetStrategy = when {
                !tagAvailable -> MangaUpdateCheckStrategy.SEARCH
                existing != null -> existing.strategy
                else -> MangaUpdateCheckStrategy.TAG
            }
            val derivedKeyword = matchedDir?.chapters?.find { it.tid == t }
                ?.let { MangaTitleCleaner.extractAuthorPrefix(it.rawTitle) }
                ?: matchedDir?.chapters?.lastOrNull()
                    ?.let { MangaTitleCleaner.extractAuthorPrefix(it.rawTitle) } ?: ""
            mangaConfigPresetKeyword = existing?.searchKeyword ?: matchedDir?.searchKeyword ?: derivedKeyword
            mangaConfigPresetBookName = existing?.cleanBookName ?: matchedDir?.cleanBookName ?: ""
            showMangaConfigDialog = true
        }
    }
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
                // 进入前台：尝试跑一轮自动更新检查（调度器自带最小间隔 + 错峰 + 登录守卫）
                AutoUpdateCheckScheduler.onAppForeground(context.applicationContext)

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

                    if (!isQuickReturn) {
                        favoriteVM.getCacheInfo { info -> cacheInfoMap = info }
                    }
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

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchBarExpanded by rememberSaveable { mutableStateOf(false) }
    val isSearching = searchQuery.isNotBlank()

    BackHandler(enabled = isSearchBarExpanded && probingUrl == null) {
        searchQuery = ""
        isSearchBarExpanded = false
    }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            // 搜索结果是当前列表的子集，过滤态下索引不再等同于 VM 中的收藏列表索引。
            // 因此搜索中禁用拖拽，避免重排错位。
            if (!isInManageMode && !isSearching) favoriteVM.moveFavorite(from.index, to.index)
        }
    )

    // 分类数据
    val categoryOptions = listOf(
        Triple(-1, "全部", Color.Transparent),
        Triple(1, "小说", Color(0xFF4CAF50)),
        Triple(2, "漫画", Color(0xFF2196F3)),
        Triple(3, "其他", Color(0xFFFF9800)),
        Triple(0, "未定", darkModeColor(Color(0xFF64748B), Color(0xFFAAAAAA)))
    )

    var currentCategoryId by rememberSaveable { mutableIntStateOf(favoriteVM.currentCategory) }
    val currentCat = categoryOptions.find { it.first == currentCategoryId } ?: categoryOptions[0]

    val searchTerms = remember(searchQuery) {
        searchQuery
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
    }

    val searchedFavoriteList = remember(favoriteList, searchTerms) {
        if (searchTerms.isEmpty()) {
            favoriteList
        } else {
            favoriteList.filter { fav ->
                val cleanTitle = fav.title
                    .replace(Regex("^(?:【.*?】|\\[.*?\\]|\\s)+"), "")
                    .ifBlank { fav.title }

                // 只搜索收藏标题，避免数字、链接、作者 ID、章节名等隐藏/辅助字段造成误命中。
                searchTerms.all { term ->
                    fav.title.contains(term, ignoreCase = true) ||
                            cleanTitle.contains(term, ignoreCase = true)
                }
            }
        }
    }

    var shouldShowEmptyState by remember { mutableStateOf(false) }

    LaunchedEffect(
        searchedFavoriteList.isEmpty(),
        isRefreshing,
        isSearching,
        favoriteList.size,
        currentCategoryId
    ) {
        shouldShowEmptyState = false
        if (searchedFavoriteList.isNotEmpty() || isRefreshing) return@LaunchedEffect

        if (isSearching) {
            // 搜索结果为空应该及时反馈；非搜索的“暂无收藏”则延迟确认，
            // 避免进入页面时 DataStore / Flow 初始空列表造成一瞬间闪屏。
            shouldShowEmptyState = true
        } else {
            delay(EMPTY_STATE_CONFIRM_DELAY_MS)
            shouldShowEmptyState = true
        }
    }

    LaunchedEffect(searchQuery, currentCategoryId) {
        if (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0) {
            lazyListState.scrollToItem(0)
        }
    }

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
    val topBarContentColor = darkThemeColor(Color.Black) { onPrimary }
    LaunchedEffect(Unit) {
        favoriteVM.refreshCacheInfo()
        SettingsUtil.getHomePage { GlobalData.homePageRoute.value = it }
    }

    @Composable
    fun MoreOptionsButton() {
        FavoriteMoreOptionsButton(
            topBarContentColor = topBarContentColor,
            isLoggedIn = isLoggedIn,
            isRefreshing = isRefreshing,
            isFavoriteCollapsed = isFavoriteCollapsed,
            isClickToTopEnabled = isClickToTopEnabled,
            isDnsOptimizationEnabled = isDnsOptimizationEnabled,
            onSetHomePage = { showHomePageDialog = true },
            onManageFavorite = { favoriteVM.toggleManageMode() },
            onToggleFavoriteCollapsed = {
                coroutineScope.launch {
                    delay(250)
                    val newState = !isFavoriteCollapsed
                    GlobalData.isFavoriteCollapsed.value = newState
                    SettingsUtil.saveFavoriteCollapseMode(newState)
                }
            },
            onManageCache = { showCacheManagement = true },
            onToggleClickToTop = {
                if (isClickToTopEnabled) {
                    coroutineScope.launch {
                        delay(250)
                        GlobalData.isClickToTopEnabled.value = false
                        SettingsUtil.saveClickToTopMode(false)
                    }
                } else {
                    showClickToTopDialog = true
                }
            },
            onManageBookmark = { showBookmarkManagement = true },
            onNetworkOptimization = { showCustomDnsDialog = true },
            onManageDirectory = {
                favoriteVM.getDirectoryList { dirs ->
                    directoryList = dirs
                    showDirectoryManagement = true
                }
            },
            onToggleAutoSignIn = {
                coroutineScope.launch {
                    delay(250)
                    val newState = !GlobalData.isAutoSignInEnabled.value
                    GlobalData.isAutoSignInEnabled.value = newState
                    SettingsUtil.saveAutoSignInMode(newState)

                    if (newState) {
                        AutoSignManager.resetQuota()
                        AutoSignManager.checkAndSignIfNeeded(
                            context,
                            force = true
                        )
                    }
                }
            },
            onRefreshList = {
                favoriteVM.refreshList(
                    showLoading = true,
                    isSmartSync = false
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .padding(bottom = lockedNavHeight + 50.dp)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (lockedStatusHeight > 0.dp) lockedStatusHeight else 28.dp)
                .background(darkThemeColor(YamiboColors.onSurface) { statusBar })
        )
        TopBar(title = "") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchBarExpanded) {
                    FavoriteTopSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        onClose = {
                            searchQuery = ""
                            isSearchBarExpanded = false
                        },
                        resultText = if (isSearching) "${searchedFavoriteList.size}项" else null,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = if (isInManageMode) 6.dp else 2.dp)
                    )
                    if (isInManageMode) {
                        FavoriteManageDoneButton(
                            onClick = { favoriteVM.toggleManageMode() }
                        )
                    } else {
                        MoreOptionsButton()
                    }
                } else {
                    // 左半部：标题或分类切换
                    if (isInManageMode) {
                        Text(
                            text = "管理收藏 (${selectedItems.size})",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = topBarContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f)
                        )
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
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .height(40.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { categoryMenuExpanded = true }
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "收藏栏/${currentCat.second}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = topBarContentColor
                                )
                                Icon(
                                    painterResource(R.drawable.ic_arrow_down),
                                    contentDescription = if (categoryMenuExpanded) "收起分类" else "展开分类",
                                    tint = topBarContentColor,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .offset(y = 2.dp)
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
                                    .background(MaterialTheme.colorScheme.surface)
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
                        IconButton(
                            onClick = { isSearchBarExpanded = true },
                            modifier = Modifier
                                .size(44.dp)
                                .padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索收藏",
                                modifier = Modifier.size(24.dp),
                                tint = if (isSearching) MaterialTheme.colorScheme.primary else topBarContentColor
                            )
                        }
                        FavoriteManageDoneButton(
                            onClick = { favoriteVM.toggleManageMode() }
                        )
                    } else {
                        IconButton(
                            onClick = { isSearchBarExpanded = true },
                            modifier = Modifier
                                .size(44.dp)
                                .padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索收藏",
                                modifier = Modifier.size(24.dp),
                                tint = if (isSearching) MaterialTheme.colorScheme.primary else topBarContentColor
                            )
                        }
                        MoreOptionsButton()
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
                    items = searchedFavoriteList,
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
                                when (item.type) {
                                    1 -> favoriteVM.clearNovelUpdateCheckFlag(item.url)
                                    2 -> favoriteVM.clearMangaUpdateCheckFlag(item.url)
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
                        val hasUpdate = novelCheckMap[item.url]?.hasUpdate == true ||
                                mangaCheckMap[item.url]?.hasUpdate == true
                        val isCheckingUpdate = uiState.checkingUpdateUrls.contains(item.url)
                        val isProbingType = probingTypeUrls.contains(item.url)
                        val updateCheckTracked = novelCheckMap[item.url] != null ||
                                mangaCheckMap[item.url] != null
                        val autoCheckEnabled = novelCheckMap[item.url]?.autoCheckEnabled == true ||
                                mangaCheckMap[item.url]?.autoCheckEnabled == true
                        val isManga = item.type == 2
                        val isUndetected = item.type == 0
                        val canSwipeCheck = !isInManageMode && (item.type == 0 || item.type == 1 || item.type == 2)
                        val canSwipeConfigure =
                            !isInManageMode &&
                                    updateCheckTracked &&
                                    (item.type == 1 || item.type == 2)

                        SwipeToCheckRow(
                            enabled = canSwipeCheck,
                            canConfigure = canSwipeConfigure,
                            modifier = Modifier.animateItem(),
                            checkLabel = if (isUndetected) {
                                if (isProbingType) "探测中" else "探测类型"
                            } else {
                                if (updateCheckTracked) "检查更新" else "追踪更新"
                            },
                            checkIcon = if (isUndetected) Icons.Default.Search else Icons.Default.Refresh,
                            checkIconRotates = !isUndetected,
                            onCheck = {
                                when (item.type) {
                                    0 -> favoriteVM.probeFavoriteTypeInBackground(item)
                                    1 -> favoriteVM.checkNovelUpdate(item)
                                    2 -> if (mangaCheckMap[item.url] != null)
                                        favoriteVM.checkMangaUpdate(item)
                                    else openMangaConfig(item)
                                }
                            },
                            onConfigure = {
                                if (isManga) openMangaConfig(item)
                                else {
                                    val profile = novelCheckMap[item.url]
                                    novelUpdateCheckTarget = item
                                    novelConfigAutoCheck = profile?.autoCheckEnabled ?: false
                                    novelConfigInterval = profile?.autoCheckIntervalHours ?: 12
                                    showNovelConfigDialog = true
                                }
                            }
                        ) {
                            FavoriteItem(
                                item.title,
                                item.lastView,
                                item.lastPage,
                                item.lastChapter,
                                onClick = currentOnClick,
                                modifier = Modifier.longPressDraggableHandle(
                                    enabled = !isInManageMode && !isSearching,
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
                                hasUpdate = hasUpdate,
                                isCheckingUpdate = isCheckingUpdate || isProbingType,
                                autoCheckEnabled = autoCheckEnabled,
                                updateCheckTracked = updateCheckTracked,
                                dragHandle = {
                                    val canDrag = !isInManageMode && !isSearching
                                    Icon(
                                        imageVector = Icons.Filled.Menu,
                                        contentDescription = if (canDrag) "拖动排序" else null,
                                        tint = if (canDrag) {
                                            darkThemeColor(YamiboColors.primary) { primary }
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (shouldShowEmptyState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Search else Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isSearching) "没有匹配的收藏" else "暂无收藏",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
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
                    color = darkThemeColor(YellowLightLight) { surface },
                    shadowElevation = 3.dp,
                    border = BorderStroke(
                        width = 2.dp,
                        color = darkThemeColor(
                            YamiboColors.primary.copy(alpha = 0.4f)
                        ) { primary.copy(alpha = 0.5f) }
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
                                YamiboToast.show(context = context, message = msg)
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
        // 漫画手动更新检查配置对话框
        if (showMangaConfigDialog && mangaUpdateCheckTarget != null) {
            val fav = mangaUpdateCheckTarget!!
            val initStrategy = mangaConfigPresetStrategy
            val initKeyword = mangaConfigPresetKeyword
            val initBookName = mangaConfigPresetBookName
            var selectedStrategy by remember(fav.url, initStrategy) { mutableStateOf(initStrategy) }
            var bookName by remember(fav.url, initBookName) { mutableStateOf(initBookName) }
            var keyword1 by remember(fav.url, initKeyword) { mutableStateOf(initKeyword) }
            var keyword2 by remember(fav.url) { mutableStateOf("") }
            var showKeyword2 by remember(fav.url) { mutableStateOf(false) }
            val existingManga = mangaCheckMap[fav.url]
            var mangaConfigAutoCheck by remember(fav.url, existingManga) { mutableStateOf(existingManga?.autoCheckEnabled ?: false) }
            var mangaConfigInterval by remember(fav.url, existingManga) {
                mutableIntStateOf(existingManga?.autoCheckIntervalHours ?: 12)
            }
            var searchCooldownSec by remember { mutableIntStateOf(0) }

            LaunchedEffect(selectedStrategy, showMangaConfigDialog) {
                while (selectedStrategy == MangaUpdateCheckStrategy.SEARCH && showMangaConfigDialog) {
                    val remaining = favoriteVM.getSearchCooldownRemainingMs()
                    searchCooldownSec = if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
                    if (searchCooldownSec == 0) break
                    delay(1000)
                }
                searchCooldownSec = 0
            }

            val searchOnCooldown = selectedStrategy == MangaUpdateCheckStrategy.SEARCH && searchCooldownSec > 0

            AlertDialog(
                onDismissRequest = { showMangaConfigDialog = false; mangaUpdateCheckTarget = null },
                title = { Text("漫画更新检查") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        val tagAvailable = mangaConfigTagAvailable

                        if (tagAvailable) {
                            Text("更新策略", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedStrategy == MangaUpdateCheckStrategy.TAG,
                                    onClick = { selectedStrategy = MangaUpdateCheckStrategy.TAG }
                                )
                                Text("标签页拉取", modifier = Modifier.clickable { selectedStrategy = MangaUpdateCheckStrategy.TAG })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedStrategy == MangaUpdateCheckStrategy.SEARCH,
                                    onClick = { selectedStrategy = MangaUpdateCheckStrategy.SEARCH }
                                )
                                Text("全局搜索", modifier = Modifier.clickable { selectedStrategy = MangaUpdateCheckStrategy.SEARCH })
                            }
                        } else {
                            Text(
                                "此漫画无标签，将使用「搜索」方式检查更新",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }

                        if (selectedStrategy == MangaUpdateCheckStrategy.SEARCH) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = keyword1,
                                    onValueChange = { keyword1 = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("关键词 1") }
                                )
                                if (showKeyword2) {
                                    OutlinedTextField(
                                        value = keyword2,
                                        onValueChange = { keyword2 = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        label = { Text("关键词 2") }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { showKeyword2 = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+", fontSize = 24.sp, fontWeight = FontWeight.Light)
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = bookName,
                                onValueChange = { bookName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("漫画名称") }
                            )
                        }

                        if (searchOnCooldown) {
                            Text(
                                "搜索冷却中，请等待 ${searchCooldownSec} 秒",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        AutoCheckSection(
                            enabled = mangaConfigAutoCheck,
                            intervalHours = mangaConfigInterval,
                            onEnabledChange = { mangaConfigAutoCheck = it },
                            onIntervalChange = { mangaConfigInterval = it },
                            enabledCount = autoEnabledCount + when {
                                mangaConfigAutoCheck && !(existingManga?.autoCheckEnabled == true) -> 1
                                !mangaConfigAutoCheck && (existingManga?.autoCheckEnabled == true) -> -1
                                else -> 0
                            },
                            maxCount = FavoriteVM.MAX_AUTO_CHECK,
                            isCurrentlyEnabled = existingManga?.autoCheckEnabled == true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showMangaConfigDialog = false
                            mangaUpdateCheckTarget?.let { target ->
                                val isSearch = selectedStrategy == MangaUpdateCheckStrategy.SEARCH
                                val combinedKeyword = if (isSearch) {
                                    listOf(keyword1.trim(), keyword2.trim())
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" ")
                                } else ""
                                favoriteVM.checkMangaUpdate(
                                    target,
                                    overrideStrategy = selectedStrategy,
                                    overrideSearchKeyword = combinedKeyword.ifBlank { null },
                                    overrideCleanBookName = if (isSearch) bookName.ifBlank { null } else null
                                )
                                favoriteVM.saveMangaAutoCheck(target.url, mangaConfigAutoCheck, mangaConfigInterval)
                            }
                            mangaUpdateCheckTarget = null
                        },
                        enabled = !searchOnCooldown
                    ) { Text(if (searchOnCooldown) "冷却中..." else "开始查询") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showMangaConfigDialog = false; mangaUpdateCheckTarget = null
                    }) { Text("取消") }
                }
            )
        }
        // 小说自动检查配置对话框
        if (showNovelConfigDialog && novelUpdateCheckTarget != null) {
            val fav = novelUpdateCheckTarget!!
            AlertDialog(
                onDismissRequest = { showNovelConfigDialog = false; novelUpdateCheckTarget = null },
                title = { Text("小说更新检查") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        AutoCheckSection(
                            enabled = novelConfigAutoCheck,
                            intervalHours = novelConfigInterval,
                            onEnabledChange = { novelConfigAutoCheck = it },
                            onIntervalChange = { novelConfigInterval = it },
                            enabledCount = autoEnabledCount + when {
                                novelConfigAutoCheck && !(novelCheckMap[fav.url]?.autoCheckEnabled == true) -> 1
                                !novelConfigAutoCheck && (novelCheckMap[fav.url]?.autoCheckEnabled == true) -> -1
                                else -> 0
                            },
                            maxCount = FavoriteVM.MAX_AUTO_CHECK,
                            isCurrentlyEnabled = novelCheckMap[fav.url]?.autoCheckEnabled == true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNovelConfigDialog = false
                        novelUpdateCheckTarget?.let { target ->
                            favoriteVM.saveNovelAutoCheck(target.url, novelConfigAutoCheck, novelConfigInterval)
                        }
                        novelUpdateCheckTarget = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNovelConfigDialog = false; novelUpdateCheckTarget = null
                    }) { Text("取消") }
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


@Composable
private fun AutoCheckSection(
    enabled: Boolean,
    intervalHours: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    enabledCount: Int,
    maxCount: Int,
    isCurrentlyEnabled: Boolean
) {
    // 仅当"本项尚未启用"且"总数已达上限"时禁止新开
    val atCapForNew = !isCurrentlyEnabled && enabledCount >= maxCount
    val intervals = FavoriteVM.AUTO_CHECK_INTERVALS
    var showCustomDialog by remember { mutableStateOf(false) }

    var customUnitDays by remember { mutableStateOf(false) }
    var customNum by remember { mutableStateOf("12") }

    fun normalizeCustomHours(numText: String, days: Boolean): Int? {
        val raw = numText.toIntOrNull() ?: return null
        if (raw <= 0) return null
        return if (days) raw.coerceIn(1, 30) * 24 else raw.coerceIn(1, 720)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动检查更新", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "名额 $enabledCount / $maxCount",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                enabled = !atCapForNew,
                onCheckedChange = onEnabledChange
            )
        }

        if (atCapForNew) {
            Text(
                "已达自动检查上限，请先关闭其它项目再开启",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        val sizeSpec = tween<IntSize>(durationMillis = 300, easing = FastOutSlowInEasing)
        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                    expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top),
            exit = fadeOut(tween(300, easing = FastOutSlowInEasing)) +
                    shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "检查间隔",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Text(
                        formatCheckInterval(intervalHours),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        intervals.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(formatCheckInterval(h)) },
                                onClick = {
                                    onIntervalChange(h)
                                    expanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("自定义...") },
                            onClick = {
                                showCustomDialog = true
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    // 每次打开自定义弹窗时，基于当前 intervalHours 初始化输入值
    LaunchedEffect(showCustomDialog) {
        if (showCustomDialog) {
            val init = if (intervalHours !in intervals) intervalHours.coerceAtLeast(1) else 12
            customUnitDays = init >= 24 && init % 24 == 0
            customNum = if (customUnitDays) (init / 24).toString() else init.toString()
        }
    }

    if (showCustomDialog) {
        val normalized = normalizeCustomHours(customNum, customUnitDays)
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = {
                Text("自定义间隔", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 输入框：使用中性灰色，不用主题色
                    OutlinedTextField(
                        value = customNum,
                        onValueChange = { value ->
                            customNum = value.filter { it.isDigit() }.take(3)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.width(86.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64748B),
                            unfocusedBorderColor = Color(0xFF94A3B8),
                            cursorColor = Color(0xFF475569)
                        )
                    )

                    // 单位切换：用 Row + 分隔线，不用胶囊边框
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val unitOptions = listOf(false to "小时", true to "天")
                        unitOptions.forEachIndexed { idx, (isDays, label) ->
                            val selected = customUnitDays == isDays
                            Box(
                                modifier = Modifier
                                    .clickable { customUnitDays = isDays }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) {
                                        Color(0xFF334155)
                                    } else {
                                        Color(0xFF94A3B8)
                                    }
                                )
                            }
                            if (idx < unitOptions.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(16.dp)
                                        .background(Color(0xFFCBD5E1))
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalized != null,
                    onClick = {
                        normalized?.let(onIntervalChange)
                        showCustomDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SwipeToCheckRow(
    enabled: Boolean,
    canConfigure: Boolean,
    onCheck: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    checkLabel: String = "检查更新",
    checkIcon: ImageVector = Icons.Default.Refresh,
    checkIconRotates: Boolean = true,
    configureLabel: String = "配置",
    configureIcon: ImageVector = Icons.Default.Build,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    val currentOnCheck by rememberUpdatedState(onCheck)
    val currentOnConfigure by rememberUpdatedState(onConfigure)

    // 触发阈值与最大可滑动距离
    val triggerPx = with(density) { 64.dp.toPx() }
    val maxLeftPx = with(density) { 96.dp.toPx() }
    val maxRightPx = if (canConfigure) with(density) { 96.dp.toPx() } else 0f
    var wasArmed by remember { mutableStateOf(false) }

    val accent = darkThemeColor(YamiboColors.primary) { primary }

    Box(modifier) {
        // 随滑动实时计算两侧揭示宽度与进度
        val leftRevealPx = (-offsetX.value).coerceAtLeast(0f)
        val rightRevealPx = if (canConfigure) offsetX.value.coerceAtLeast(0f) else 0f
        val leftProgress = (leftRevealPx / triggerPx).coerceIn(0f, 1f)
        val rightProgress = (rightRevealPx / triggerPx).coerceIn(0f, 1f)
        val leftArmed = offsetX.value <= -triggerPx
        val rightArmed = canConfigure && offsetX.value >= triggerPx
        // 面板比揭示宽度多延伸 12dp 伸到卡片圆角下面，消除缝隙
        val cornerOverlapPx = with(density) { 12.dp.toPx() }

        // 背后揭示的操作面板：与卡片同样内缩 5dp、同样 12dp 圆角，保证整体感
        Box(
            Modifier
                .matchParentSize()
                .padding(5.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (leftRevealPx > 0.5f) {
                SwipeActionPanel(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    revealPx = leftRevealPx + cornerOverlapPx,
                    progress = leftProgress,
                    armed = leftArmed,
                    icon = checkIcon,
                    label = checkLabel,
                    accent = accent,
                    iconRotation = if (checkIconRotates) leftProgress * 180f else 0f
                )
            }
            if (rightRevealPx > 0.5f) {
                SwipeActionPanel(
                    modifier = Modifier.align(Alignment.CenterStart),
                    revealPx = rightRevealPx + cornerOverlapPx,
                    progress = rightProgress,
                    armed = rightArmed,
                    icon = configureIcon,
                    label = configureLabel,
                    accent = accent,
                    iconRotation = 0f
                )
            }
        }

        // 前景：可横向拖动的收藏卡片
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(canConfigure) {
                    detectHorizontalDragGestures(
                        onDragStart = { wasArmed = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val target =
                                (offsetX.value + dragAmount).coerceIn(-maxLeftPx, maxRightPx)
                            scope.launch { offsetX.snapTo(target) }
                            val armed =
                                target <= -triggerPx || (canConfigure && target >= triggerPx)
                            if (armed && !wasArmed) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            wasArmed = armed
                        },
                        onDragEnd = {
                            val x = offsetX.value
                            scope.launch {
                                offsetX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                            }
                            when {
                                x <= -triggerPx -> currentOnCheck()
                                canConfigure && x >= triggerPx -> currentOnConfigure()
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

/**
 * 左/右滑动揭示的操作面板。
 *
 * 设计目标：与收藏卡片同高、同圆角；背景由「浅色着色 -> 实色强调色」平滑过渡，
 * 跨过触发阈值(armed)时图标弹性放大、配色翻转，给出清晰的「即将触发」反馈。
 */
@Composable
private fun SwipeActionPanel(
    revealPx: Float,
    progress: Float,
    armed: Boolean,
    icon: ImageVector,
    label: String,
    accent: Color,
    iconRotation: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val widthDp = with(density) { revealPx.toDp() }

    // armed 的弹性过渡，带一点回弹让「触发就绪」更有手感
    val arm by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "swipe_action_arm"
    )

    // 依据强调色亮度挑选对比色，避免浅色主题下白色图标看不清
    val accentLuma = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
    val onAccent = if (accentLuma > 0.6f) Color(0xFF1A1A1A) else Color.White

    val panelColor = lerp(accent.copy(alpha = 0.16f), accent, arm)
    val contentColor = lerp(accent, onAccent, arm)
    val iconScale = (0.72f + 0.28f * progress) * (1f + 0.14f * arm)
    val contentAlpha = (0.15f + progress).coerceIn(0f, 1f)
    val labelAlpha = ((widthDp.value - 46f) / 20f).coerceIn(0f, 1f)

    Box(
        modifier
            .width(widthDp)
            .fillMaxHeight()
            .background(panelColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        rotationZ = iconRotation
                    }
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = labelAlpha }
            )
        }
    }
}

@Composable
private fun FavoriteMoreOptionsButton(
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

@Composable
private fun FavoriteManageDoneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .padding(start = 2.dp)
            .width(68.dp)
            .height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(
            text = "完成",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FavoriteTopSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    resultText: String? = null,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val borderColor = if (value.isBlank()) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val supportColor = MaterialTheme.colorScheme.onSurfaceVariant
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (value.isBlank()) supportColor else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = contentColor,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = "搜索标题",
                                color = supportColor.copy(alpha = 0.72f),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (!resultText.isNullOrBlank()) {
                Text(
                    text = resultText,
                    color = supportColor.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp)
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "收起搜索",
                    modifier = Modifier.size(20.dp),
                    tint = supportColor
                )
            }
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
    val lightModeTheme by GlobalData.lightModeTheme.collectAsState()

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
                                },
                                colors = if (lightModeTheme > 0) SwitchDefaults.colors(
                                    uncheckedTrackColor = Color(0xFFCBD5E1),
                                    uncheckedBorderColor = Color(0xFFCBD5E1)
                                ) else SwitchDefaults.colors()
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
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFF94A3B8),
                        focusedBorderColor = Color(0xFF64748B)
                    ),
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
