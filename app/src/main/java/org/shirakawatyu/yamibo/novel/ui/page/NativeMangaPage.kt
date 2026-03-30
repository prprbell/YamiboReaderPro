package org.shirakawatyu.yamibo.novel.ui.page

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    var activePointers by remember { mutableIntStateOf(0) }
    val isMultiTouch by remember { derivedStateOf { activePointers > 1 } }
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
    val navigateToChapter = { targetUrl: String ->
        showUi = false
        showChapterList = false
        probingUrl = targetUrl // 触发黑屏遮罩
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

                    // 成功：跳转到新的阅读器，并踢掉当前阅读器（保持原本的返回栈逻辑和进场动画）
                    navController.navigate("NativeMangaPage?url=$encodedChapterUrl&originalUrl=$encodedOriginalUrl") {
                        if (previousRoute?.startsWith("MangaWebPage") == true) {
                            popUpTo(previousRoute) { inclusive = true }
                        } else {
                            navController.currentDestination?.id?.let { currentId ->
                                popUpTo(currentId) { inclusive = true }
                            }
                        }
                    }

                    scope.launch {
                        delay(300)
                        probingUrl = null
                        probingJob = null
                    }
                },
                onFallback = {
                    isJumpingChapter = false
                    navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false&initialPage=0") {
                        if (previousRoute?.startsWith("MangaWebPage") == true) {
                            popUpTo(previousRoute) { inclusive = true }
                        } else {
                            navController.currentDestination?.id?.let { currentId ->
                                popUpTo(currentId) { inclusive = true }
                            }
                        }
                    }
                    probingUrl = null
                    probingJob = null
                }
            )
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
        isExiting = true
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
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
        isExiting = true
        favoriteVM.nextResumeStrategy = FavoriteVM.RefreshStrategy.SMART
        val window = activity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)

        val previousRoute = navController.previousBackStackEntry?.destination?.route

        if (previousRoute?.startsWith("MangaWebPage") == true || previousRoute == "BBSPage" || previousRoute == "MinePage") {
            navController.navigateUp()
        } else {
            val encodedChapterUrl = URLEncoder.encode(url, "utf-8")
            val encodedOriginalUrl = URLEncoder.encode(originalUrl, "utf-8")
            navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=true&initialPage=0") {
                navController.currentDestination?.id?.let { currentId ->
                    popUpTo(currentId) { inclusive = true }
                }
            }
        }
        Unit
    }

    BackHandler(enabled = true) {
        if (probingUrl != null) {
            // 正在加载下一话时按返回，取消探测，停留在当前话
            probingJob?.cancel()
            probingJob = null
            probingUrl = null
            isJumpingChapter = false
        } else {
            performExit()
        }
    }

    var isFirstEnter by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(showUi, lifecycleOwner.lifecycle.currentState) {
        if (isExiting) return@LaunchedEffect

        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (showUi) {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    controller.isAppearanceLightStatusBars = false
                    isFirstEnter = false // 若用户提前点击，取消延迟
                } else {
                    if (isFirstEnter) {
                        delay(150)
                        isFirstEnter = false
                    }

                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())

                    delay(300)

                    controller.isAppearanceLightStatusBars = false
                }
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = true
                bottomNavBarVM.setBottomNavBarVisibility(true)
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
            bottomNavBarVM.setBottomNavBarVisibility(false)
        }

        onDispose {
            if (!isJumpingChapter) {
                val win = activity?.window
                if (win != null) {
                    val ctrl = WindowCompat.getInsetsController(win, view)
                    ctrl.show(WindowInsetsCompat.Type.systemBars())
                }
                bottomNavBarVM.setBottomNavBarVisibility(true)
            }
        }
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
                    delay(150)

                    val totalPages = imageUrls.size

                    val loadSequence = mutableListOf(currentIndex)
                    val maxOffset = maxOf(currentIndex, totalPages - 1 - currentIndex)
                    for (offset in 1..maxOffset) {
                        if (currentIndex + offset < totalPages) loadSequence.add(currentIndex + offset)
                        if (currentIndex - offset >= 0) loadSequence.add(currentIndex - offset)
                    }

                    val nearbyCount = 8.coerceAtMost(loadSequence.size)
                    val nearbyBatch = loadSequence.subList(0, nearbyCount)
                    val restBatch = if (nearbyCount < loadSequence.size)
                        loadSequence.subList(nearbyCount, loadSequence.size) else emptyList()

                    withContext(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            allowedIndices = allowedIndices + nearbyBatch.toSet()
                        }

                        if (currentIndex in imageUrls.indices) {
                            val req = ImageRequest.Builder(context.applicationContext)
                                .data(imageUrls[currentIndex])
                                .addHeader("Cookie", cookie)
                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                            imageLoader.execute(req)
                        }

                        for (i in nearbyBatch) {
                            if (i == currentIndex || i !in imageUrls.indices) continue
                            val req = ImageRequest.Builder(context.applicationContext)
                                .data(imageUrls[i])
                                .addHeader("Cookie", cookie)
                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                            imageLoader.enqueue(req)
                        }

                        if (restBatch.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                allowedIndices = allowedIndices + restBatch.toSet()
                            }
                            val batchSize = 3
                            for (batch in restBatch.chunked(batchSize)) {
                                kotlinx.coroutines.coroutineScope {
                                    batch.filter { it in imageUrls.indices }.forEach { i ->
                                        launch {
                                            val req = ImageRequest.Builder(context.applicationContext)
                                                .data(imageUrls[i])
                                                .addHeader("Cookie", cookie)
                                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .build()
                                            imageLoader.execute(req)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(currentIndex, mangaDirVM.currentDirectory) {
                    val currentTid =
                        MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect
                    val chapter =
                        mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentTid }
                    if (chapter != null) {
                        val displayNum = when {
                            chapter.rawTitle.contains(Regex("番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画", RegexOption.IGNORE_CASE)) -> "SP"
                            chapter.chapterNum == 999f -> "终"
                            chapter.chapterNum < 1f && !chapter.rawTitle.contains(Regex("0|零|〇")) -> "Ex"
                            else -> {
                                val safeStr = java.text.DecimalFormat("0.###").format(chapter.chapterNum)
                                if (safeStr.contains(".")) {
                                    val parts = safeStr.split(".")
                                    if (parts[1].length >= 3) "Ex" else "${parts[0]}-${parts[1].trimStart('0')}"
                                } else safeStr
                            }
                        }
                        val shortTitle = "读至第 $displayNum 话 - ${currentIndex + 1}页"

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
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastVolKeyTime > 300L) {
                                            lastVolKeyTime = currentTime
                                            scope.launch {
                                                if (isVerticalMode) {
                                                    val target =
                                                        if (isVolDown) lazyListState.firstVisibleItemIndex + 1 else lazyListState.firstVisibleItemIndex - 1
                                                    lazyListState.animateScrollToItem(
                                                        index = target.coerceIn(
                                                            0,
                                                            imageUrls.size - 1
                                                        )
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
                            }
                            false
                        }
                        .pointerInput(isVerticalMode) {
                            if (!isVerticalMode) return@pointerInput
                            awaitPointerEventScope {
                                var isPanning = false
                                var panOffset = Offset.Zero
                                var isHorizontalPan = false
                                val touchSlop = viewConfiguration.touchSlop
                                val velocityTracker = VelocityTracker()

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val activePointers = event.changes.filter { it.pressed }
                                    event.changes.forEach { change ->
                                        if (change.pressed && change.positionChanged()) {
                                            velocityTracker.addPointerInputChange(change)
                                        }
                                    }
                                    if (activePointers.size > 1) {
                                        // 双指缩放时，重置滑动和速度状态，防止松手时乱飘
                                        isPanning = false
                                        panOffset = Offset.Zero
                                        isHorizontalPan = false
                                        velocityTracker.resetTracking()

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
                                                val newOffsetX =
                                                    globalOffsetX.value * scaleDelta + panChange.x
                                                val newOffsetY =
                                                    globalOffsetY.value * scaleDelta + panChange.y

                                                val maxX = (screenWidthPx * (newScale - 1)) / 2f
                                                val maxY = (screenHeightPx * (newScale - 1)) / 2f

                                                globalScale.snapTo(newScale)
                                                if (newScale > 1f) {
                                                    globalOffsetX.snapTo(
                                                        newOffsetX.coerceIn(
                                                            -maxX,
                                                            maxX
                                                        )
                                                    )
                                                    globalOffsetY.snapTo(
                                                        newOffsetY.coerceIn(
                                                            -maxY,
                                                            maxY
                                                        )
                                                    )
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
                                                    isHorizontalPan =
                                                        kotlin.math.abs(panOffset.x) > kotlin.math.abs(
                                                            panOffset.y
                                                        )
                                                }
                                            }

                                            if (isPanning) {
                                                val scale = globalScale.value
                                                val maxX = (screenWidthPx * (scale - 1)) / 2f
                                                val maxY = (screenHeightPx * (scale - 1)) / 2f

                                                val targetX = globalOffsetX.value + panChange.x
                                                val targetY = globalOffsetY.value + panChange.y

                                                val clampedY = targetY.coerceIn(-maxY, maxY)
                                                val canMoveY =
                                                    kotlin.math.abs(clampedY - globalOffsetY.value) > 0.01f

                                                if (canMoveY) {
                                                    activePointers.forEach { if (it.positionChanged()) it.consume() }
                                                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                                        globalOffsetX.snapTo(
                                                            targetX.coerceIn(
                                                                -maxX,
                                                                maxX
                                                            )
                                                        )
                                                        globalOffsetY.snapTo(clampedY)
                                                    }
                                                } else {
                                                    if (isHorizontalPan && kotlin.math.abs(panChange.x) > kotlin.math.abs(
                                                            panChange.y
                                                        )
                                                    ) {
                                                        activePointers.forEach { if (it.positionChanged()) it.consume() }
                                                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                                            globalOffsetX.snapTo(
                                                                targetX.coerceIn(
                                                                    -maxX,
                                                                    maxX
                                                                )
                                                            )
                                                        }
                                                    } else {
                                                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                                            globalOffsetX.snapTo(
                                                                targetX.coerceIn(
                                                                    -maxX,
                                                                    maxX
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (activePointers.isEmpty()) {
                                        if (isPanning) {
                                            val velocity = velocityTracker.calculateVelocity()
                                            val scale = globalScale.value
                                            val maxX = (screenWidthPx * (scale - 1)) / 2f
                                            val maxY = (screenHeightPx * (scale - 1)) / 2f

                                            if (isHorizontalPan) {
                                                scope.launch {
                                                    globalOffsetX.updateBounds(-maxX, maxX)
                                                    globalOffsetY.updateBounds(-maxY, maxY)

                                                    launch {
                                                        globalOffsetX.animateDecay(
                                                            initialVelocity = velocity.x,
                                                            animationSpec = exponentialDecay()
                                                        )
                                                    }
                                                    launch {
                                                        globalOffsetY.animateDecay(
                                                            initialVelocity = velocity.y,
                                                            animationSpec = exponentialDecay()
                                                        )
                                                    }
                                                }
                                            } else {
                                                scope.launch {
                                                    globalOffsetX.updateBounds(-maxX, maxX)
                                                    globalOffsetY.updateBounds(-maxY, maxY)
                                                    launch {
                                                        globalOffsetX.animateDecay(
                                                            initialVelocity = velocity.x,
                                                            animationSpec = exponentialDecay()
                                                        )
                                                    }
                                                    launch {
                                                        globalOffsetY.animateDecay(
                                                            initialVelocity = velocity.y,
                                                            animationSpec = exponentialDecay()
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // 彻底重置所有状态
                                        isPanning = false
                                        panOffset = Offset.Zero
                                        isHorizontalPan = false
                                        velocityTracker.resetTracking()
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
                                    SubcomposeAsyncImage(
                                        model = request,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 400.dp),
                                        contentScale = ContentScale.FillWidth,
                                        loading = {
                                            // 在图片真实加载完成前，持续显示加载圈
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(color = YamiboColors.tertiary)
                                            }
                                        }
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
                                        var isImageLoading by remember(imgUrl) { mutableStateOf(true) }
                                        val request = remember(imgUrl) {
                                            ImageRequest.Builder(context.applicationContext)
                                                .data(imgUrl)
                                                .addHeader("Cookie", cookie)
                                                .addHeader("Referer", "https://bbs.yamibo.com/")
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .crossfade(false)
                                                .listener(
                                                    onStart = { isImageLoading = true },
                                                    onSuccess = { _, _ -> isImageLoading = false },
                                                    onError = { _, _ -> isImageLoading = false },
                                                    onCancel = { isImageLoading = false }
                                                )
                                                .build()
                                        }
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            ZoomableAsyncImage(
                                                model = request,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                                onClick = horizontalPagerClick
                                            )

                                            // 如果图片还在下载中，在这个Box的中间显示加载圈
                                            if (isImageLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.align(Alignment.Center),
                                                    color = YamiboColors.tertiary
                                                )
                                            }
                                            AnimatedVisibility(
                                                visible = probingUrl != null,
                                                enter = fadeIn(tween(0)), // 瞬间变黑
                                                exit = fadeOut(tween(150)),
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
                            if (chap != null) {
                                val displayNum = when {
                                    chap.rawTitle.contains(
                                        Regex(
                                            "番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画",
                                            RegexOption.IGNORE_CASE
                                        )
                                    ) -> "SP"

                                    chap.chapterNum == 999f -> "终"
                                    chap.chapterNum < 1f && !chap.rawTitle.contains(Regex("0|零|〇")) -> "Ex"
                                    else -> {
                                        val safeStr =
                                            java.text.DecimalFormat("0.###").format(chap.chapterNum)
                                        if (safeStr.contains(".")) {
                                            val parts = safeStr.split(".")
                                            if (parts[1].length >= 3) "Ex" else "${parts[0]}-${
                                                parts[1].trimStart(
                                                    '0'
                                                )
                                            }"
                                        } else safeStr
                                    }
                                }
                                Text(
                                    "第 $displayNum 话",
                                    color = Color.LightGray, fontSize = 12.sp
                                )
                            }
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
                            // 进度条区域
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

                    // 提取初始的作者名
                    val initialAuthor = remember(mangaDirVM.currentDirectory, currentTid) {
                        val dir = mangaDirVM.currentDirectory

                        if (dir?.searchKeyword != null && dir.searchKeyword != dir.cleanBookName) {
                            dir.searchKeyword.replace(dir.cleanBookName, "").trim()
                        } else {
                            val currentChapter = dir?.chapters?.find { it.tid == currentTid }
                            if (currentChapter != null) {
                                MangaTitleCleaner.extractAuthorPrefix(currentChapter.rawTitle)
                            } else {
                                dir?.chapters?.lastOrNull()?.let {
                                    MangaTitleCleaner.extractAuthorPrefix(it.rawTitle)
                                } ?: ""
                            }
                        }
                    }

                    MangaChapterPanel(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        title = mangaDirVM.currentDirectory?.cleanBookName ?: "目录",
                        initialAuthor = initialAuthor,
                        chapters = displayChapters,
                        isUpdating = mangaDirVM.isUpdatingDirectory,
                        cooldownSeconds = mangaDirVM.directoryCooldown,
                        strategy = mangaDirVM.currentDirectory?.strategy,
                        showSearchShortcut = mangaDirVM.showSearchShortcut,
                        searchShortcutCountdown = mangaDirVM.searchShortcutCountdown,
                        onUpdateClick = { isForced ->
                            val tid = MangaTitleCleaner.extractTidFromUrl(url)
                            mangaDirVM.updateMangaDirectory(isForced, tid)
                        },
                        onDismiss = {
                            showChapterList = false
                            showUi = false
                        },
                        onTitleEdit = { newTitle, newAuthor ->
                            val newSearchKeyword =
                                if (newAuthor.isNotBlank()) "$newAuthor $newTitle".trim() else newTitle.trim()
                            mangaDirVM.renameDirectory(newTitle.trim(), newSearchKeyword)
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