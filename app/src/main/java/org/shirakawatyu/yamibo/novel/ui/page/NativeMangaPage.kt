package org.shirakawatyu.yamibo.novel.ui.page

import android.app.Activity
import android.os.Build
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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import org.shirakawatyu.yamibo.novel.bean.MangaSettings
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class)
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

    val mangaDirVM: MangaDirectoryVM =
        viewModel(factory = ViewModelFactory(context.applicationContext))
    val favoriteVM: FavoriteVM = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = ViewModelFactory(context.applicationContext)
    )
    val bottomNavBarVM: BottomNavBarVM = viewModel(viewModelStoreOwner = context)

    // 持久化读取偏好
    var readMode by rememberSaveable { mutableIntStateOf(MangaSettings.getSettings(context).readMode) }
    val isVerticalMode = readMode == 0
    var imageBrightness by rememberSaveable { mutableFloatStateOf(1f) }
    var imageUrls by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var initialIndex by rememberSaveable { mutableIntStateOf(0) }
    var showUi by rememberSaveable { mutableStateOf(false) }
    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showSettingsPanel by rememberSaveable { mutableStateOf(false) }

    val globalScale = remember { Animatable(1f) }
    val globalOffsetX = remember { Animatable(0f) }
    val globalOffsetY = remember { Animatable(0f) }

    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val isRtl = readMode == 2
    // 追踪屏幕上的手指数量，用于精准识别多指手势并锁定页面滑动
    var activePointers by remember { mutableIntStateOf(0) }
    val isMultiTouch by remember { derivedStateOf { activePointers > 1 } }

    // ====== 修复闪图与无响应核心：稳定化点击回调 ======
    // 将 Lambda 包裹在 remember 中，避免因 showUi 的变化导致图片组件无谓重组
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
    // 通过安全的官方 API 检查上一级页面是谁
    val previousRoute = navController.previousBackStackEntry?.destination?.route

    // 统一定义跳转章节的逻辑
    val navigateToChapter = { targetUrl: String ->
        showUi = false
        showChapterList = false
        val encodedChapterUrl = URLEncoder.encode(targetUrl, "utf-8")
        val encodedOriginalUrl = URLEncoder.encode(originalUrl, "utf-8")

        navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false&initialPage=0") {
            if (previousRoute?.startsWith("MangaWebPage") == true) {
                // 如果是从 BBS 帖子进入的，切换章节时，把旧的那一话 MangaWebPage 连带干掉，防止无限堆栈
                popUpTo(previousRoute) { inclusive = true }
            } else {
                // 如果是从收藏夹进入的，直接干掉当前的 NativeMangaPage 即可
                navController.currentDestination?.id?.let { currentId ->
                    popUpTo(currentId) { inclusive = true }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (GlobalData.tempMangaUrls.isNotEmpty()) {
            imageUrls = GlobalData.tempMangaUrls
            initialIndex = GlobalData.tempMangaIndex
            if (GlobalData.tempHtml.isNotBlank()) {
                mangaDirVM.initDirectoryFromWeb(url, GlobalData.tempHtml, GlobalData.tempTitle)
            } else {
                if (mangaDirVM.currentDirectory == null) {
                    mangaDirVM.loadDirectoryByUrl(url)
                }
            }
            GlobalData.tempMangaUrls = emptyList()
            GlobalData.tempHtml = ""
            GlobalData.tempMangaIndex = 0
        } else {
            if (mangaDirVM.currentDirectory == null) {
                mangaDirVM.loadDirectoryByUrl(url)
            }
        }
    }


    val performExit = {
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = true
            }
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
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = true
            }
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)

        val previousRoute = navController.previousBackStackEntry?.destination?.route

        if (previousRoute?.startsWith("MangaWebPage") == true || previousRoute == "BBSPage" || previousRoute == "MinePage") {
            navController.navigateUp()
        } else {
            val encodedChapterUrl = URLEncoder.encode(url, "utf-8")
            val encodedOriginalUrl = URLEncoder.encode(originalUrl, "utf-8")
            navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false&initialPage=0") {
                navController.currentDestination?.id?.let { currentId ->
                    popUpTo(currentId) { inclusive = true }
                }
            }
        }
        Unit
    }

    BackHandler { performExit() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(showUi, lifecycleOwner.lifecycle.currentState) {
        val window = activity?.window
        if (window != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (showUi) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                controller.isAppearanceLightStatusBars = false
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }

        if (window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            window.statusBarColor = android.graphics.Color.TRANSPARENT
            controller.isAppearanceLightStatusBars = false

            if (!showUi) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            bottomNavBarVM.setBottomNavBarVisibility(false)
        }

        onDispose {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        activePointers = event.changes.count { it.pressed }
                    }
                }
            }
    ) {
        if (imageUrls.isNotEmpty()) {
            val cookie = CookieManager.getInstance().getCookie("https://bbs.yamibo.com") ?: ""
            val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            val pagerState =
                rememberPagerState(initialPage = initialIndex, pageCount = { imageUrls.size })

            val currentIndex by remember(isVerticalMode, lazyListState, pagerState) {
                derivedStateOf {
                    if (isVerticalMode) lazyListState.firstVisibleItemIndex else pagerState.currentPage
                }
            }
            if (imageUrls.isNotEmpty()) {
                val cookie = CookieManager.getInstance().getCookie("https://bbs.yamibo.com") ?: ""
                val lazyListState =
                    rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                val pagerState =
                    rememberPagerState(initialPage = initialIndex, pageCount = { imageUrls.size })

                val currentIndex by remember(isVerticalMode, lazyListState, pagerState) {
                    derivedStateOf {
                        if (isVerticalMode) lazyListState.firstVisibleItemIndex else pagerState.currentPage
                    }
                }

                var allowedIndices by remember { mutableStateOf(setOf(currentIndex)) }

                val imageLoader = context.imageLoader
                LaunchedEffect(currentIndex, imageUrls) {
                    if (imageUrls.isEmpty()) return@LaunchedEffect
                    delay(250)
                    val loadSequence = listOf(
                        currentIndex,
                        currentIndex + 1,
                        currentIndex + 2,
                        currentIndex + 3,
                        currentIndex - 1
                    )

                    // 切入 IO 线程池执行任务
                    withContext(Dispatchers.IO) {
                        // 第一步：强制优先执行当前页的下载请求，并挂起协程直到其加载完成
                        if (currentIndex in imageUrls.indices) {
                            withContext(Dispatchers.Main) {
                                allowedIndices = allowedIndices + currentIndex
                            }

                            val currentRequest = ImageRequest.Builder(context.applicationContext)
                                .data(imageUrls[currentIndex])
                                .addHeader("Cookie", cookie)
                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()

                            // execute 会阻塞此协程直到本张图片加载完成（缓存命中则瞬间返回）
                            imageLoader.execute(currentRequest)
                        }

                        // 第二步：当前页一旦加载完毕，解锁附近页面的 UI 加载权限
                        withContext(Dispatchers.Main) {
                            allowedIndices = allowedIndices + loadSequence
                        }

                        // 第三步：继续静默预加载其他页面，此时网络带宽不会再影响当前页
                        for (i in loadSequence) {
                            if (i == currentIndex || i !in imageUrls.indices) continue

                            val request = ImageRequest.Builder(context.applicationContext)
                                .data(imageUrls[i])
                                .addHeader("Cookie", cookie)
                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()

                            imageLoader.execute(request)
                        }
                    }
                }

                LaunchedEffect(currentIndex, mangaDirVM.currentDirectory) {
                    val currentTid =
                        MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect
                    val chapter =
                        mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentTid }
                    if (chapter != null) {
                        val shortTitle =
                            if (chapter.chapterNum % 1f == 0f) "读至第 ${chapter.chapterNum.toInt()} 话 - ${currentIndex + 1}页" else "读至第 ${chapter.chapterNum} 话 - ${currentIndex + 1}页"

                        favoriteVM.updateMangaProgress(originalUrl, url, shortTitle, currentIndex)
                    }
                }

                val horizontalPagerClick: (Offset) -> Unit =
                    remember(screenWidthPx, isRtl, pagerState) {
                        { offset ->
                            if (showUi) {
                                showUi = false
                                showSettingsPanel = false
                            } else {
                                val isLeftTap = offset.x < screenWidthPx * 0.15f
                                val isRightTap = offset.x > screenWidthPx * 0.85f

                                if (isLeftTap) {
                                    scope.launch {
                                        val targetPage =
                                            if (isRtl) pagerState.targetPage + 1 else pagerState.targetPage - 1
                                        pagerState.animateScrollToPage(
                                            page = targetPage.coerceIn(0, pagerState.pageCount - 1),
                                            animationSpec = tween(durationMillis = 250)
                                        )
                                    }
                                } else if (isRightTap) {
                                    scope.launch {
                                        val targetPage =
                                            if (isRtl) pagerState.currentPage - 1 else pagerState.currentPage + 1
                                        pagerState.animateScrollToPage(
                                            targetPage.coerceIn(
                                                0,
                                                pagerState.pageCount - 1
                                            )
                                        )
                                    }
                                } else {
                                    showUi = true
                                }
                            }
                        }
                    }
                // 音量键焦点请求器
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(showUi) {
                    if (!showUi) {
                        view.isFocusableInTouchMode = true
                        view.requestFocus()
                        delay(50)
                        try {
                            focusRequester.requestFocus()
                        } catch (e: Exception) {
                        }
                    }
                }
                // 图片渲染区
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            val isVolDown = event.key == Key.VolumeDown
                            val isVolUp = event.key == Key.VolumeUp

                            if (isVolDown || isVolUp) {
                                if (!showUi) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        scope.launch {
                                            if (isVerticalMode) {
                                                val target =
                                                    if (isVolDown) lazyListState.firstVisibleItemIndex + 1 else lazyListState.firstVisibleItemIndex - 1
                                                lazyListState.animateScrollToItem(
                                                    index = target.coerceIn(0, imageUrls.size - 1)
                                                )
                                            } else {
                                                val target =
                                                    if (isVolDown) pagerState.targetPage + 1 else pagerState.targetPage - 1
                                                pagerState.animateScrollToPage(
                                                    page = target.coerceIn(
                                                        0,
                                                        pagerState.pageCount - 1
                                                    ),
                                                    animationSpec = tween(durationMillis = 250)
                                                )
                                            }
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                        .pointerInput(isVerticalMode) {
                            if (!isVerticalMode) return@pointerInput
                            awaitPointerEventScope {
                                var isPanning = false
                                var panOffset = Offset.Zero
                                val touchSlop = viewConfiguration.touchSlop

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val activePointers = event.changes.filter { it.pressed }

                                    if (activePointers.size > 1) {
                                        isPanning = false // 重置单指状态
                                        panOffset = Offset.Zero

                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        if (zoomChange != 1f || panChange != Offset.Zero) {
                                            activePointers.forEach { if (it.positionChanged()) it.consume() }

                                            scope.launch {
                                                val oldScale = globalScale.value
                                                val dampenedZoom = 1f + (zoomChange - 1f) * 0.6f
                                                val newScale =
                                                    (oldScale * dampenedZoom).coerceIn(1f, 4f)

                                                val scaleDelta = newScale / oldScale
                                                var newOffsetX =
                                                    globalOffsetX.value * scaleDelta + panChange.x
                                                var newOffsetY =
                                                    globalOffsetY.value * scaleDelta + panChange.y

                                                val maxX = (screenWidthPx * (newScale - 1)) / 2f
                                                val maxY = (screenHeightPx * (newScale - 1)) / 2f

                                                newOffsetX = newOffsetX.coerceIn(-maxX, maxX)
                                                newOffsetY = newOffsetY.coerceIn(-maxY, maxY)

                                                globalScale.snapTo(newScale)
                                                if (newScale > 1f) {
                                                    globalOffsetX.snapTo(newOffsetX)
                                                    globalOffsetY.snapTo(newOffsetY)
                                                } else {
                                                    globalOffsetX.snapTo(0f)
                                                    globalOffsetY.snapTo(0f)
                                                }
                                            }
                                        }
                                    } else if (activePointers.size == 1 && globalScale.value > 1f) {
                                        val panChange = event.calculatePan()
                                        if (panChange != Offset.Zero) {
                                            if (!isPanning) {
                                                panOffset += panChange
                                                if (panOffset.getDistance() > touchSlop) {
                                                    isPanning = true
                                                }
                                            }

                                            if (isPanning) {
                                                val scale = globalScale.value
                                                val maxX = (screenWidthPx * (scale - 1)) / 2f
                                                val maxY = (screenHeightPx * (scale - 1)) / 2f

                                                val isAtTopLimit = !lazyListState.canScrollBackward
                                                val isAtBottomLimit =
                                                    !lazyListState.canScrollForward

                                                val shouldInterceptY =
                                                    (isAtTopLimit && panChange.y > 0) || (isAtBottomLimit && panChange.y < 0)

                                                if (shouldInterceptY) {
                                                    activePointers.forEach { if (it.positionChanged()) it.consume() }

                                                    scope.launch {
                                                        val newOffsetX =
                                                            (globalOffsetX.value + panChange.x).coerceIn(
                                                                -maxX,
                                                                maxX
                                                            )
                                                        globalOffsetX.snapTo(newOffsetX)

                                                        val targetOffsetY =
                                                            (globalOffsetY.value + panChange.y).coerceIn(
                                                                -maxY,
                                                                maxY
                                                            )
                                                        globalOffsetY.snapTo(targetOffsetY)
                                                    }
                                                } else {
                                                    scope.launch {
                                                        val newOffsetX =
                                                            (globalOffsetX.value + panChange.x).coerceIn(
                                                                -maxX,
                                                                maxX
                                                            )
                                                        globalOffsetX.snapTo(newOffsetX)
                                                    }
                                                }
                                            }
                                        }
                                    } else if (activePointers.isEmpty()) {
                                        isPanning = false
                                        panOffset = Offset.Zero
                                    }
                                }
                            }
                        }
                        .pointerInput(isVerticalMode) {
                            if (isVerticalMode) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        scope.launch {
                                            if (globalScale.value > 1f) {
                                                launch { globalScale.animateTo(1f, tween(300)) }
                                                launch { globalOffsetX.animateTo(0f, tween(300)) }
                                                launch { globalOffsetY.animateTo(0f, tween(300)) }
                                            } else {
                                                val targetScale = 2.0f
                                                val centerPx =
                                                    Offset(screenWidthPx / 2f, screenHeightPx / 2f)
                                                val targetOffsetX =
                                                    (centerPx.x - tapOffset.x) * (targetScale - 1f)
                                                val targetOffsetY =
                                                    (centerPx.y - tapOffset.y) * (targetScale - 1f)
                                                val maxX = (screenWidthPx * (targetScale - 1)) / 2f
                                                val maxY = (screenHeightPx * (targetScale - 1)) / 2f

                                                launch {
                                                    globalScale.animateTo(
                                                        targetScale,
                                                        tween(300)
                                                    )
                                                }
                                                launch {
                                                    globalOffsetX.animateTo(
                                                        targetOffsetX.coerceIn(
                                                            -maxX,
                                                            maxX
                                                        ), tween(300)
                                                    )
                                                }
                                                launch {
                                                    globalOffsetY.animateTo(
                                                        targetOffsetY.coerceIn(
                                                            -maxY,
                                                            maxY
                                                        ), tween(300)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onTap = { handleVerticalClick() }
                                )
                            }
                        }
                ) {
                    if (isVerticalMode) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = globalScale.value
                                    scaleY = globalScale.value
                                    translationX = globalOffsetX.value
                                    translationY = globalOffsetY.value
                                },
                            state = lazyListState,
                            userScrollEnabled = true,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(imageUrls, key = { index, _ -> index }) { index, imgUrl ->
                                if (index in allowedIndices) {
                                    val request = remember(imgUrl) {
                                        ImageRequest.Builder(context.applicationContext)
                                            .data(imgUrl)
                                            .addHeader("Cookie", cookie)
                                            .addHeader("Referer", "https://bbs.yamibo.com/")
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .crossfade(false)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = request,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 400.dp),
                                        contentScale = ContentScale.FillWidth
                                    )
                                } else {
                                    // 还没轮到它加载，使用标准 Box 占位
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 400.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = Color.LightGray)
                                    }
                                }
                            }
                        }
                    } else {
                        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                pageSpacing = 16.dp,
                                userScrollEnabled = !isMultiTouch
                            ) { page ->
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    if (page in allowedIndices) {
                                        val imgUrl = imageUrls[page]
                                        val request = remember(imgUrl) {
                                            ImageRequest.Builder(context.applicationContext)
                                                .data(imgUrl)
                                                .addHeader("Cookie", cookie)
                                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .crossfade(false)
                                                .build()
                                        }
                                        ZoomableAsyncImage(
                                            model = request,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit,
                                            onClick = horizontalPagerClick
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(color = YamiboColors.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (imageBrightness < 1f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 1f - imageBrightness))
                    )
                }

                // 顶部栏
                AnimatedVisibility(
                    visible = showUi && !showChapterList && !showSettingsPanel,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
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
                        IconButton(onClick = performExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                mangaDirVM.currentDirectory?.cleanBookName ?: "漫画阅读",
                                color = Color.White,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val chap = mangaDirVM.currentDirectory?.chapters?.find {
                                it.tid == MangaTitleCleaner.extractTidFromUrl(url)
                            }
                            if (chap != null) Text(
                                if (chap.chapterNum % 1f == 0f) "第 ${chap.chapterNum.toInt()} 话" else "第 ${chap.chapterNum} 话",
                                color = Color.LightGray, fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = returnToOriginalPost) {
                            Text("去往原帖", color = YamiboColors.tertiary)
                        }
                    }
                }

                // 底部悬浮菜单栏
                AnimatedVisibility(
                    visible = showUi && !showChapterList && !showSettingsPanel,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                    exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp) // 先设置左右悬浮边距
                        .padding(bottom = 6.dp)     // 再设置底部悬浮边距
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1E1E22).copy(alpha = 0.90f))
                            .pointerInput(Unit) { detectTapGestures {} }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally // 让内部元素整体居中
                        ) {
                            // 1. 进度条区域
                            if (imageUrls.size > 1) {
                                // 计算上一话和下一话
                                val currentTid = MangaTitleCleaner.extractTidFromUrl(url)
                                val sortedChapters =
                                    remember(mangaDirVM.currentDirectory?.chapters) {
                                        mangaDirVM.currentDirectory?.chapters?.sortedBy { it.chapterNum }
                                    }
                                val currentChapIndex = remember(sortedChapters, currentTid) {
                                    sortedChapters?.indexOfFirst { it.tid == currentTid } ?: -1
                                }

                                val prevChapter =
                                    if (currentChapIndex > 0) sortedChapters?.get(currentChapIndex - 1) else null
                                val nextChapter =
                                    if (currentChapIndex != -1 && sortedChapters != null && currentChapIndex < sortedChapters.size - 1) sortedChapters[currentChapIndex + 1] else null

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左侧：上一话箭头按钮
                                    IconButton(
                                        onClick = {
                                            prevChapter?.url?.let { targetUrl ->
                                                navigateToChapter(targetUrl)
                                            }
                                        },
                                        enabled = prevChapter != null
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "上一话",
                                            tint = if (prevChapter != null) Color.White else Color.DarkGray
                                        )
                                    }

                                    // 中间：进度条
                                    Slider(
                                        value = currentIndex.toFloat(),
                                        valueRange = 0f..(imageUrls.size - 1).toFloat(),
                                        onValueChange = { target ->
                                            scope.launch {
                                                if (isVerticalMode) {
                                                    lazyListState.scrollToItem(target.toInt())
                                                } else {
                                                    pagerState.scrollToPage(target.toInt())
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp)
                                            .height(24.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White,
                                            inactiveTrackColor = Color(0xFF4A4A52)
                                        )
                                    )

                                    // 右侧：下一话箭头按钮
                                    IconButton(
                                        onClick = {
                                            nextChapter?.url?.let { targetUrl ->
                                                navigateToChapter(targetUrl)
                                            }
                                        },
                                        enabled = nextChapter != null
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "下一话",
                                            tint = if (nextChapter != null) Color.White else Color.DarkGray
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // 2. 底部操作栏（设置 - 页码 - 目录）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左侧：设置
                                TextButton(
                                    onClick = { showSettingsPanel = true },
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 8.dp
                                    )
                                ) {
                                    Text(
                                        "设置",
                                        color = Color.LightGray,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.width(50.dp))
                                // 中间：页码胶囊指示器
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x552C2C32)) // 微微凸显的底色块
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${currentIndex + 1} / ${imageUrls.size}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(50.dp))
                                // 右侧：目录
                                TextButton(
                                    onClick = { showChapterList = true },
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 8.dp
                                    )
                                ) {
                                    Text(
                                        "目录",
                                        color = Color.LightGray,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                if (showChapterList) {
                    val currentTid = MangaTitleCleaner.extractTidFromUrl(url)
                    val displayChapters = mangaDirVM.currentDirectory?.chapters?.map {
                        MangaChapter(
                            it.chapterNum,
                            it.rawTitle,
                            it.url,
                            isCurrent = it.tid == currentTid,
                            isRead = false
                        )
                    } ?: emptyList()
                    MangaChapterPanel(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        title = mangaDirVM.currentDirectory?.cleanBookName ?: "目录",
                        chapters = displayChapters,
                        isUpdating = mangaDirVM.isUpdatingDirectory,
                        cooldownSeconds = mangaDirVM.directoryCooldown,
                        strategy = mangaDirVM.currentDirectory?.strategy,
                        showSearchShortcut = mangaDirVM.showSearchShortcut,
                        searchShortcutCountdown = mangaDirVM.searchShortcutCountdown,
                        onUpdateClick = { mangaDirVM.updateMangaDirectory(it) },
                        onDismiss = {
                            showChapterList = false
                            showUi = false
                        },
                        onTitleEdit = { newTitle ->
                            mangaDirVM.renameDirectory(newTitle)
                        },
                        onChapterClick = { chapter ->
                            if (chapter.url.isNotEmpty()) {
                                navigateToChapter(chapter.url)
                            }
                        }
                    )
                }
                if (showSettingsPanel) {
                    MangaSettingsPanel(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        currentMode = readMode,
                        brightness = imageBrightness,
                        onModeChange = { index ->
                            val targetPage = currentIndex
                            MangaSettings.saveReadMode(context, index)
                            readMode = index

                            scope.launch {
                                if (index == 0) lazyListState.scrollToItem(targetPage)
                                else pagerState.scrollToPage(targetPage)
                            }
                        },
                        onBrightnessChange = { imageBrightness = it },
                        onDismiss = {
                            showSettingsPanel = false
                            showUi = false
                        },
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = YamiboColors.primary
                )
            }
        }
    }
}