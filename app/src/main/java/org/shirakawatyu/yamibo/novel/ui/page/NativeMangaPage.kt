package org.shirakawatyu.yamibo.novel.ui.page

import android.app.Activity
import android.content.ComponentCallbacks2
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import org.shirakawatyu.yamibo.novel.bean.MangaSettings
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.MangaReaderManager
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import org.shirakawatyu.yamibo.novel.util.ZoomPanGestureHandler
import org.shirakawatyu.yamibo.novel.util.verticalMangaZoomGesture
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun NativeMangaPage(
    url: String,
    originalUrl: String = url,
    navController: NavController
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val mangaDirVM: MangaDirectoryVM = viewModel(factory = ViewModelFactory(context.applicationContext))
    val favoriteVM: FavoriteVM = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = ViewModelFactory(context.applicationContext)
    )
    val bottomNavBarVM: BottomNavBarVM = viewModel(viewModelStoreOwner = context)

    var readMode by rememberSaveable { mutableIntStateOf(MangaSettings.getSettings(context).readMode) }
    val isVerticalMode = readMode == 0
    var imageBrightness by rememberSaveable { mutableFloatStateOf(1f) }
    var initialIndex by rememberSaveable { mutableIntStateOf(0) }
    var showUi by rememberSaveable { mutableStateOf(false) }
    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showSettingsPanel by rememberSaveable { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    var isJumpingChapter by remember { mutableStateOf(false) }
    val globalScale = remember { Animatable(1f) }
    val globalOffsetX = remember { Animatable(0f) }
    val globalOffsetY = remember { Animatable(0f) }
    var probingUrl by remember { mutableStateOf<String?>(null) }
    var probingJob by remember { mutableStateOf<Job?>(null) }

    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val isRtl = readMode == 2

    var isMultiTouch by remember { mutableStateOf(false) }
    var lastVolKeyTime by remember { mutableLongStateOf(0L) }

    val handleVerticalClick: () -> Unit = remember {
        {
            if (showUi) {
                showUi = false
                showSettingsPanel = false
            } else {
                showUi = true
            }
        }
    }
    val previousRoute = navController.previousBackStackEntry?.destination?.route

    val getDisplayChapterNum = remember {
        { rawTitle: String, chapterNum: Float ->
            when {
                rawTitle.contains(Regex("番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画", RegexOption.IGNORE_CASE)) -> "SP"
                chapterNum == 999f -> "终"
                chapterNum < 1f && !rawTitle.contains(Regex("0|零|〇")) -> "Ex"
                else -> {
                    val safeStr = java.text.DecimalFormat("0.###").format(chapterNum)
                    if (safeStr.contains(".")) {
                        val parts = safeStr.split(".")
                        if (parts[1].length >= 3) "Ex" else "${parts[0]}-${parts[1].trimStart('0')}"
                    } else safeStr
                }
            }
        }
    }

    val fallbackNavigate = { targetUrl: String ->
        showUi = false
        showChapterList = false
        probingUrl = targetUrl
        isJumpingChapter = true

        val encodedChapterUrl = URLEncoder.encode(targetUrl, "utf-8")
        val encodedOriginalUrl = URLEncoder.encode(originalUrl, "utf-8")

        probingJob = scope.launch {
            org.shirakawatyu.yamibo.novel.util.MangaProber().probeUrl(
                context = context,
                url = targetUrl,
                onSuccess = { urls, title, html ->
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempHtml = html
                    GlobalData.tempTitle = title
                    GlobalData.tempMangaIndex = 0

                    navController.navigate("NativeMangaPage?url=$encodedChapterUrl&originalUrl=$encodedOriginalUrl") {
                        if (previousRoute?.startsWith("MangaWebPage") == true) {
                            popUpTo(previousRoute) { inclusive = true }
                        } else {
                            navController.currentDestination?.id?.let { currentId -> popUpTo(currentId) { inclusive = true } }
                        }
                    }
                    scope.launch { delay(300); probingUrl = null; probingJob = null }
                },
                onFallback = {
                    isJumpingChapter = false
                    navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false&initialPage=0") {
                        if (previousRoute?.startsWith("MangaWebPage") == true) {
                            popUpTo(previousRoute) { inclusive = true }
                        } else {
                            navController.currentDestination?.id?.let { currentId -> popUpTo(currentId) { inclusive = true } }
                        }
                    }
                    probingUrl = null; probingJob = null
                }
            )
        }
    }

    val readerManager = remember {
        MangaReaderManager(context, mangaDirVM, scope) { fallbackUrl -> fallbackNavigate(fallbackUrl) }
    }

    LaunchedEffect(Unit) {
        if (GlobalData.tempMangaUrls.isNotEmpty()) {
            val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: ""
            readerManager.initFirstChapter(tid, url, GlobalData.tempTitle, GlobalData.tempMangaUrls)
            initialIndex = GlobalData.tempMangaIndex

            if (GlobalData.tempHtml.isNotBlank()) {
                mangaDirVM.initDirectoryFromWeb(url, GlobalData.tempHtml, GlobalData.tempTitle)
            } else {
                if (mangaDirVM.currentDirectory == null) mangaDirVM.loadDirectoryByUrl(url)
            }
            GlobalData.tempMangaUrls = emptyList()
            GlobalData.tempHtml = ""
            GlobalData.tempMangaIndex = 0
        } else {
            if (mangaDirVM.currentDirectory == null) mangaDirVM.loadDirectoryByUrl(url)
        }
    }

    val performExit = {
        isExiting = true
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply { show(WindowInsetsCompat.Type.systemBars()) }
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)
        val prevEntry = navController.previousBackStackEntry
        if (prevEntry?.destination?.route?.startsWith("MangaWebPage") == true) {
            navController.popBackStack(prevEntry.destination.id, inclusive = true)
        } else {
            navController.navigateUp()
        }
        Unit
    }

    val returnToOriginalPost = {
        isExiting = true
        favoriteVM.nextResumeStrategy = FavoriteVM.RefreshStrategy.SMART
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply { show(WindowInsetsCompat.Type.systemBars()) }
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)

        if (previousRoute?.startsWith("MangaWebPage") == true || previousRoute == "BBSPage" || previousRoute == "MinePage") {
            navController.navigateUp()
        } else {
            val encodedChapterUrl = URLEncoder.encode(url, "utf-8")
            val encodedOriginalUrl = URLEncoder.encode(originalUrl, "utf-8")
            navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=true&initialPage=0") {
                navController.currentDestination?.id?.let { currentId -> popUpTo(currentId) { inclusive = true } }
            }
        }
        Unit
    }

    BackHandler(enabled = true) {
        if (probingUrl != null) {
            probingJob?.cancel(); probingJob = null; probingUrl = null; isJumpingChapter = false
        } else performExit()
    }

    var isFirstEnter by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(showUi, lifecycleOwner.lifecycle.currentState) {
        if (isExiting) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (showUi) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                controller.isAppearanceLightStatusBars = false
                isFirstEnter = false
            } else {
                if (isFirstEnter) { delay(150); isFirstEnter = false }
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                delay(300)
                controller.isAppearanceLightStatusBars = false
            }
        }
    }

    val gestureHandler = remember {
        ZoomPanGestureHandler(scale = globalScale, offsetX = globalOffsetX, offsetY = globalOffsetY, screenWidthPx = screenWidthPx, screenHeightPx = screenHeightPx)
    }

    DisposableEffect(lifecycleOwner) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            }
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            bottomNavBarVM.setBottomNavBarVisibility(false)
        }
        onDispose {
            if (!isJumpingChapter) {
                activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
                bottomNavBarVM.setBottomNavBarVisibility(true)
                context.imageLoader.memoryCache?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            }
        }
    }

    val cookieState = produceState(initialValue = "-1") {
        value = withContext(Dispatchers.IO) { CookieManager.getInstance().getCookie("https://bbs.yamibo.com") ?: "" }
    }
    val cookie = cookieState.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val active = event.changes.count { it.pressed } > 1
                        if (isMultiTouch != active) {
                            isMultiTouch = active
                        }
                    }
                }
            }
    ) {
        if (readerManager.flatPages.isNotEmpty() && cookie != "-1") {
            val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { readerManager.flatPages.size })

            val currentIndex by remember(isVerticalMode, lazyListState, pagerState) {
                derivedStateOf {
                    if (isVerticalMode) {
                        val layoutInfo = lazyListState.layoutInfo
                        if (layoutInfo.visibleItemsInfo.isEmpty()) {
                            lazyListState.firstVisibleItemIndex
                        } else {
                            val readLine = layoutInfo.viewportStartOffset + (layoutInfo.viewportSize.height / 3)
                            var activeIndex = lazyListState.firstVisibleItemIndex
                            for (itemInfo in layoutInfo.visibleItemsInfo) {
                                if (readLine >= itemInfo.offset && readLine <= (itemInfo.offset + itemInfo.size)) {
                                    activeIndex = itemInfo.index
                                    break
                                }
                            }
                            activeIndex
                        }
                    } else {
                        pagerState.currentPage
                    }
                }
            }

            val currentItem = readerManager.flatPages.getOrNull(currentIndex)

            var showChapterToast by remember { mutableStateOf(false) }
            var toastChapterText by remember { mutableStateOf("") }
            var previousTid by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(currentItem?.tid) {
                val currentTid = currentItem?.tid ?: return@LaunchedEffect
                if (previousTid != null && previousTid != currentTid) {
                    val chap = mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentTid }
                    if (chap != null) {
                        val displayNum = getDisplayChapterNum(chap.rawTitle, chap.chapterNum)
                        toastChapterText = "第 $displayNum 话"
                    }
                }
                previousTid = currentTid
            }

            LaunchedEffect(toastChapterText) {
                if (toastChapterText.isNotBlank()) {
                    showChapterToast = true
                    delay(1050)
                    showChapterToast = false
                    delay(350)
                    toastChapterText = ""
                }
            }

            LaunchedEffect(currentItem?.tid, currentItem?.localIndex, mangaDirVM.currentDirectory) {
                val tid = currentItem?.tid ?: return@LaunchedEffect
                val chapter = mangaDirVM.currentDirectory?.chapters?.find { it.tid == tid }
                if (chapter != null) {
                    val displayNum = getDisplayChapterNum(chapter.rawTitle, chapter.chapterNum)
                    val shortTitle = "读至第 $displayNum 话 - ${currentItem.localIndex + 1}页"
                    favoriteVM.updateMangaProgress(originalUrl, currentItem.chapterUrl, shortTitle, currentItem.localIndex)
                }
            }

            val density = LocalDensity.current.density
            val triggerDistancePx = 120f * density
            val showUiDistancePx = 30f * density

            var pullOverscrollAmount by remember { mutableFloatStateOf(0f) }
            var hasTriggeredHaptic by remember { mutableStateOf(false) }

            LaunchedEffect(pullOverscrollAmount) {
                if (pullOverscrollAmount >= triggerDistancePx) {
                    if (!hasTriggeredHaptic) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        hasTriggeredHaptic = true
                    }
                } else {
                    hasTriggeredHaptic = false
                }
            }

            val nestedScrollConnection = remember(isVerticalMode, isRtl, lazyListState, pagerState, density) {
                object : NestedScrollConnection {

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.Drag || source.toString() == "UserInput") {
                            if (isVerticalMode) {
                                val isAtTop = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

                                if (available.y < 0 && pullOverscrollAmount > 0) {
                                    val consume = available.y.coerceAtLeast(-pullOverscrollAmount)
                                    pullOverscrollAmount += consume
                                    return Offset(0f, consume)
                                }
                                else if (available.y > 0 && isAtTop) {
                                    val dragMultiplier = (1f - (pullOverscrollAmount / (triggerDistancePx * 2.5f))).coerceIn(0.2f, 1f)
                                    pullOverscrollAmount += available.y * dragMultiplier
                                    return Offset(0f, available.y)
                                }
                            } else {
                                val isAtStart = pagerState.currentPage == 0

                                val isPullingToLoadPrev = if (isRtl) available.x < 0 else available.x > 0
                                val isPushingBack = if (isRtl) available.x > 0 else available.x < 0

                                if (isPushingBack && pullOverscrollAmount > 0) {
                                    val absX = kotlin.math.abs(available.x)
                                    val consume = absX.coerceAtMost(pullOverscrollAmount)
                                    pullOverscrollAmount -= consume
                                    return Offset(if (available.x > 0) consume else -consume, 0f)
                                } else if (isPullingToLoadPrev && isAtStart) {
                                    val absX = kotlin.math.abs(available.x)
                                    val dragMultiplier = (1f - (pullOverscrollAmount / (triggerDistancePx * 2.5f))).coerceIn(0.15f, 1f)
                                    pullOverscrollAmount += absX * dragMultiplier
                                    return Offset(available.x, 0f)
                                }
                            }
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (pullOverscrollAmount >= triggerDistancePx) {
                            val initialSize = readerManager.flatPages.size
                            readerManager.loadPrevious(isManualJump = false) {
                                scope.launch {
                                    if (isVerticalMode) {
                                        withTimeoutOrNull(1500) {
                                            snapshotFlow { lazyListState.layoutInfo.totalItemsCount }
                                                .first { it > initialSize }
                                        }
                                        lazyListState.animateScrollBy(
                                            value = -800f,
                                            animationSpec = tween(
                                                durationMillis = 400,
                                                easing = androidx.compose.animation.core.FastOutSlowInEasing
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        pullOverscrollAmount = 0f
                        hasTriggeredHaptic = false
                        return Velocity.Zero
                    }
                }
            }

            LaunchedEffect(currentIndex, readerManager.flatPages.size) {
                if (currentIndex >= readerManager.flatPages.size - 6) {
                    readerManager.loadNext(isManualJump = false)
                }
            }

            var allowedUniqueIds by remember { mutableStateOf(setOf<String>()) }
            val imageLoader = context.imageLoader

            LaunchedEffect(currentIndex, readerManager.flatPages.size) {
                val pagesSnapshot = readerManager.flatPages
                if (pagesSnapshot.isEmpty() || currentIndex !in pagesSnapshot.indices) return@LaunchedEffect
                val windowStart = maxOf(0, currentIndex - 2)
                val windowEnd = minOf(pagesSnapshot.size - 1, currentIndex + 4)
                allowedUniqueIds = pagesSnapshot.subList(windowStart, windowEnd + 1).map { it.uniqueId }.toSet()
            }

            LaunchedEffect(cookie) {
                snapshotFlow { currentIndex }
                    .distinctUntilChanged()
                    .debounce(250L)
                    .collectLatest { index ->
                        val pagesSnapshot = readerManager.flatPages
                        if (pagesSnapshot.isEmpty() || index !in pagesSnapshot.indices) return@collectLatest

                        val totalSize = pagesSnapshot.size
                        val windowStart = maxOf(0, index - 3)
                        val windowEnd = minOf(totalSize - 1, index + 6)

                        val urlsToLoad = pagesSnapshot.subList(windowStart, windowEnd + 1)
                            .sortedBy { kotlin.math.abs(it.globalIndex - index) }
                            .map { it.imageUrl }
                            .distinct()

                        withContext(Dispatchers.IO) {
                            urlsToLoad.forEach { url ->
                                launch {
                                    ensureActive()
                                    val request = ImageRequest.Builder(context.applicationContext)
                                        .data(url)
                                        .addHeader("Cookie", cookie)
                                        .addHeader("Referer", "https://bbs.yamibo.com/")
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .build()
                                    imageLoader.execute(request)
                                }
                            }
                        }
                    }
            }

            val horizontalPagerClick: (Offset) -> Unit = remember(screenWidthPx, isRtl, pagerState) {
                { offset ->
                    if (showUi) { showUi = false; showSettingsPanel = false }
                    else {
                        val isLeftTap = offset.x < screenWidthPx * 0.15f
                        val isRightTap = offset.x > screenWidthPx * 0.85f

                        if (isLeftTap) {
                            scope.launch {
                                val targetPage = if (isRtl) pagerState.targetPage + 1 else pagerState.targetPage - 1
                                pagerState.animateScrollToPage(page = targetPage.coerceIn(0, pagerState.pageCount - 1), animationSpec = tween(durationMillis = 250))
                            }
                        } else if (isRightTap) {
                            scope.launch {
                                val targetPage = if (isRtl) pagerState.currentPage - 1 else pagerState.currentPage + 1
                                pagerState.animateScrollToPage(targetPage.coerceIn(0, pagerState.pageCount - 1))
                            }
                        } else { showUi = true }
                    }
                }
            }

            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(showUi) {
                if (!showUi) {
                    view.isFocusableInTouchMode = true; view.requestFocus(); delay(50)
                    try { focusRequester.requestFocus() } catch (e: Exception) {}
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val isVolDown = event.key == Key.VolumeDown
                        val isVolUp = event.key == Key.VolumeUp
                        if (isVolDown || isVolUp) {
                            if (!showUi && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastVolKeyTime > 300L) {
                                    lastVolKeyTime = currentTime
                                    scope.launch {
                                        if (isVerticalMode) {
                                            val target = if (isVolDown) lazyListState.firstVisibleItemIndex + 1 else lazyListState.firstVisibleItemIndex - 1
                                            lazyListState.animateScrollToItem(index = target.coerceIn(0, readerManager.flatPages.size - 1))
                                        } else {
                                            val target = if (isVolDown) pagerState.targetPage + 1 else pagerState.targetPage - 1
                                            pagerState.animateScrollToPage(page = target.coerceIn(0, pagerState.pageCount - 1), animationSpec = tween(durationMillis = 250))
                                        }
                                    }
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .verticalMangaZoomGesture(
                        handler = gestureHandler, scope = scope, onTap = handleVerticalClick, enabled = isVerticalMode
                    )
            ) {
                if (isVerticalMode) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = globalScale.value; scaleY = globalScale.value; translationX = globalOffsetX.value; translationY = globalOffsetY.value },
                        state = lazyListState,
                        userScrollEnabled = true,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        itemsIndexed(
                            items = readerManager.flatPages,
                            key = { _, item -> item.uniqueId }
                        ) { _, item ->
                            if (item.uniqueId in allowedUniqueIds) {
                                val request = remember(item.imageUrl, cookie) {
                                    ImageRequest.Builder(context.applicationContext)
                                        .data(item.imageUrl).addHeader("Cookie", cookie).addHeader("Referer", "https://bbs.yamibo.com/")
                                        .memoryCachePolicy(CachePolicy.ENABLED).crossfade(false).build()
                                }
                                SubcomposeAsyncImage(
                                    model = request, contentDescription = null, contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp),
                                    loading = { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = YamiboColors.tertiary) } }
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.LightGray) }
                            }
                        }

                    }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        HorizontalPager(
                            state = pagerState,
                            key = { readerManager.flatPages.getOrNull(it)?.uniqueId ?: it },
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 16.dp,
                            userScrollEnabled = !isMultiTouch
                        ) { page ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                val item = readerManager.flatPages.getOrNull(page)

                                if (item != null && item.uniqueId in allowedUniqueIds) {
                                    var isImageLoading by remember(item.imageUrl) { mutableStateOf(true) }
                                    val request = remember(item.imageUrl, cookie) {
                                        ImageRequest.Builder(context.applicationContext)
                                            .data(item.imageUrl).addHeader("Cookie", cookie).addHeader("Referer", "https://bbs.yamibo.com/")
                                            .memoryCachePolicy(CachePolicy.ENABLED).crossfade(false)
                                            .listener(onStart = { isImageLoading = true }, onSuccess = { _, _ -> isImageLoading = false }, onError = { _, _ -> isImageLoading = false }, onCancel = { isImageLoading = false }).build()
                                    }
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        ZoomableAsyncImage(
                                            model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onClick = horizontalPagerClick
                                        )
                                        if (isImageLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = YamiboColors.tertiary)
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = YamiboColors.primary) }
                                }
                            }
                        }
                    }
                }

                val showPullUi by remember { derivedStateOf { pullOverscrollAmount > showUiDistancePx } }

                AnimatedVisibility(
                    visible = showPullUi,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).zIndex(50f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        val isReady = pullOverscrollAmount >= triggerDistancePx
                        val tipText = when {
                            isVerticalMode -> if (isReady) "松开加载上一话" else "下拉加载上一话"
                            isRtl -> if (isReady) "松开加载上一话" else "左滑加载上一话"
                            else -> if (isReady) "松开加载上一话" else "右滑加载上一话"
                        }

                        Text(
                            text = tipText,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                AnimatedVisibility(
                    visible = readerManager.isLoadingPrev && !readerManager.isManualJumping,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .zIndex(50f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = YamiboColors.tertiary,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                AnimatedVisibility(
                    visible = probingUrl != null || readerManager.isManualJumping,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.zIndex(100f)
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).pointerInput(Unit) { detectTapGestures { } }) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
                    }
                }

                AnimatedVisibility(
                    visible = showChapterToast,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 200.dp)
                        .zIndex(60f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E1E22).copy(alpha = 0.85f))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = toastChapterText,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (imageBrightness < 1f) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - imageBrightness)).pointerInput(Unit){})
            }

            AnimatedVisibility(
                visible = showUi && !showChapterList && !showSettingsPanel,
                enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .pointerInput(Unit) { detectTapGestures {} }
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = performExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            text = currentItem?.chapterTitle ?: mangaDirVM.currentDirectory?.cleanBookName ?: "漫画阅读",
                            color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val chap = mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentItem?.tid }
                        if (chap != null) {
                            val displayNum = getDisplayChapterNum(chap.rawTitle, chap.chapterNum)
                            Text("第 $displayNum 话", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = returnToOriginalPost) { Text("去往原帖", color = YamiboColors.tertiary) }
                }
            }

            AnimatedVisibility(
                visible = showUi && !showChapterList && !showSettingsPanel,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }, exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 6.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E22).copy(alpha = 0.90f)).pointerInput(Unit) { detectTapGestures {} }) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                        if (currentItem != null) {
                            val sortedChapters = mangaDirVM.currentDirectory?.chapters
                            val currentChapIndex = sortedChapters?.indexOfFirst { it.tid == currentItem.tid } ?: -1
                            val prevChapter = if (currentChapIndex > 0) sortedChapters?.get(currentChapIndex - 1) else null
                            val nextChapter = if (currentChapIndex != -1 && sortedChapters != null && currentChapIndex < sortedChapters.size - 1) sortedChapters[currentChapIndex + 1] else null

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        prevChapter?.url?.let { targetUrl ->
                                            readerManager.jumpToChapter(targetUrl) { globalIdx ->
                                                scope.launch { if (isVerticalMode) lazyListState.scrollToItem(globalIdx) else pagerState.scrollToPage(globalIdx) }
                                            }
                                        }
                                    },
                                    enabled = prevChapter != null
                                ) { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一话", tint = if (prevChapter != null) Color.White else Color.DarkGray) }

                                Slider(
                                    value = currentItem.localIndex.toFloat(),
                                    valueRange = 0f..maxOf(0f, (currentItem.chapterTotalPages - 1).toFloat()),
                                    onValueChange = { targetLocal ->
                                        val targetGlobal = currentItem.globalIndex - currentItem.localIndex + targetLocal.toInt()
                                        scope.launch {
                                            if (isVerticalMode) lazyListState.scrollToItem(targetGlobal) else pagerState.scrollToPage(targetGlobal)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(24.dp),
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF4A4A52))
                                )

                                IconButton(
                                    onClick = {
                                        nextChapter?.url?.let { targetUrl ->
                                            readerManager.jumpToChapter(targetUrl) { globalIdx ->
                                                scope.launch { if (isVerticalMode) lazyListState.scrollToItem(globalIdx) else pagerState.scrollToPage(globalIdx) }
                                            }
                                        }
                                    },
                                    enabled = nextChapter != null
                                ) { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一话", tint = if (nextChapter != null) Color.White else Color.DarkGray) }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showSettingsPanel = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("设置", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.width(50.dp))
                            Box(modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x552C2C32)).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                val displayLocal = (currentItem?.localIndex ?: 0) + 1
                                val displayTotal = currentItem?.chapterTotalPages ?: 1
                                Text(text = "$displayLocal / $displayTotal", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(50.dp))
                            TextButton(onClick = { showChapterList = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("目录", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (showChapterList) {
                val currentTid = currentItem?.tid ?: ""
                val displayChapters = mangaDirVM.currentDirectory?.chapters?.map {
                    org.shirakawatyu.yamibo.novel.ui.page.MangaChapter(it.chapterNum, it.rawTitle, it.url, isCurrent = it.tid == currentTid, isRead = false)
                } ?: emptyList()

                val initialAuthor = remember(mangaDirVM.currentDirectory, currentTid) {
                    val dir = mangaDirVM.currentDirectory
                    if (dir?.searchKeyword != null && dir.searchKeyword != dir.cleanBookName) {
                        dir.searchKeyword.replace(dir.cleanBookName, "").trim()
                    } else {
                        val chap = dir?.chapters?.find { it.tid == currentTid }
                        if (chap != null) MangaTitleCleaner.extractAuthorPrefix(chap.rawTitle)
                        else dir?.chapters?.lastOrNull()?.let { MangaTitleCleaner.extractAuthorPrefix(it.rawTitle) } ?: ""
                    }
                }

                MangaChapterPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    title = mangaDirVM.currentDirectory?.cleanBookName ?: "目录",
                    initialAuthor = initialAuthor, chapters = displayChapters, isUpdating = mangaDirVM.isUpdatingDirectory,
                    cooldownSeconds = mangaDirVM.directoryCooldown, strategy = mangaDirVM.currentDirectory?.strategy,
                    showSearchShortcut = mangaDirVM.showSearchShortcut, searchShortcutCountdown = mangaDirVM.searchShortcutCountdown,
                    onUpdateClick = { isForced ->
                        mangaDirVM.updateMangaDirectory(isForced, currentTid)
                    },
                    onDismiss = { showChapterList = false; showUi = false },
                    onTitleEdit = { newTitle, newAuthor ->
                        val newSearchKeyword = if (newAuthor.isNotBlank()) "$newAuthor $newTitle".trim() else newTitle.trim()
                        mangaDirVM.renameDirectory(newTitle.trim(), newSearchKeyword)
                    },
                    onChapterClick = { chapter ->
                        if (chapter.url.isNotEmpty()) {
                            readerManager.jumpToChapter(chapter.url) { globalIdx ->
                                scope.launch {
                                    showUi = false
                                    showChapterList = false
                                    if (isVerticalMode) lazyListState.scrollToItem(globalIdx) else pagerState.scrollToPage(globalIdx)
                                }
                            }
                        }
                    }
                )
            }
            if (showSettingsPanel) {
                MangaSettingsPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    currentMode = readMode, brightness = imageBrightness,
                    onModeChange = { index ->
                        val targetPage = currentIndex
                        MangaSettings.saveReadMode(context, index)
                        readMode = index
                        scope.launch {
                            if (index == 0) lazyListState.scrollToItem(targetPage) else pagerState.scrollToPage(targetPage)
                        }
                    },
                    onBrightnessChange = { imageBrightness = it },
                    onDismiss = { showSettingsPanel = false; showUi = false },
                )
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = YamiboColors.primary)
        }
    }
}