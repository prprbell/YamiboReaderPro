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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import org.shirakawatyu.yamibo.novel.bean.MangaSettings
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    // 1. 双指手势：以屏幕中心为原点，等比例缩放平移量
    val transformableState =
        rememberTransformableState { zoomChange, panChange, _ ->
            if (isVerticalMode) {
                scope.launch {
                    val oldScale = globalScale.value
                    val dampenedZoom = 1f + (zoomChange - 1f) * 0.4f
                    val newScale = (oldScale * dampenedZoom).coerceIn(1f, 4f)

                    val scaleDelta = newScale / oldScale
                    var newOffsetX = globalOffsetX.value * scaleDelta + panChange.x
                    var newOffsetY = globalOffsetY.value * scaleDelta + panChange.y

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
        }

    // 2. 单指手势：放大后允许左右平移画面
    val draggableState = rememberDraggableState { delta ->
        if (globalScale.value > 1f && isVerticalMode) {
            scope.launch {
                val maxX = (screenWidthPx * (globalScale.value - 1)) / 2f
                val newOffsetX = (globalOffsetX.value + delta).coerceIn(-maxX, maxX)
                globalOffsetX.snapTo(newOffsetX)
            }
        }
    }

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

    val handleHorizontalClick: (Offset) -> Unit = remember(screenWidthPx, isRtl) {
        { offset ->
            if (showUi) {
                showUi = false
                showSettingsPanel = false
            } else {
                val isLeftTap = offset.x < screenWidthPx * 0.2f
                val isRightTap = offset.x > screenWidthPx * 0.8f

                if (isLeftTap) {
                    scope.launch {
                        // TODO: 需在后续获取 pagerState, 此处为了稳定剥离会通过外层传入，见下方横屏调用处
                    }
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
            }else {
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
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = true // 提前切回暗色图标，防看不见
            }
        }

        bottomNavBarVM.setBottomNavBarVisibility(true)
        val previousRoute = navController.previousBackStackEntry?.destination?.route

        if (previousRoute?.startsWith("MangaWebPage") == true) {
            navController.popBackStack(route = previousRoute, inclusive = true)
        } else {
            navController.navigateUp()
        }
        Unit
    }

    val returnToOriginalPost = {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = true
            }
        }

        bottomNavBarVM.setBottomNavBarVisibility(true)
        navController.popBackStack()
        Unit
    }

    BackHandler { performExit() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(showUi, lifecycleOwner.lifecycle.currentState) {
        val window = activity?.window
        if (window != null && lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
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

            val imageLoader = context.imageLoader
            LaunchedEffect(currentIndex, imageUrls) {
                if (imageUrls.isEmpty()) return@LaunchedEffect
                val prefetchRange = (currentIndex - 2)..(currentIndex + 3)
                for (i in prefetchRange) {
                    if (i in imageUrls.indices) {
                        val request = ImageRequest.Builder(context.applicationContext)
                            .data(imageUrls[i])
                            .addHeader("Cookie", cookie)
                            .addHeader("Referer", "https://bbs.yamibo.com/")
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }

            LaunchedEffect(currentIndex, mangaDirVM.currentDirectory) {
                val currentTid = MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect
                val chapter = mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentTid }
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
                            val isLeftTap = offset.x < screenWidthPx * 0.2f
                            val isRightTap = offset.x > screenWidthPx * 0.8f

                            if (isLeftTap) {
                                scope.launch {
                                    val targetPage =
                                        if (isRtl) pagerState.currentPage + 1 else pagerState.currentPage - 1
                                    pagerState.animateScrollToPage(
                                        targetPage.coerceIn(
                                            0,
                                            pagerState.pageCount - 1
                                        )
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
                    kotlinx.coroutines.delay(50)
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
                                                target.coerceIn(
                                                    0,
                                                    imageUrls.size - 1
                                                )
                                            )
                                        } else {
                                            val target =
                                                if (isVolDown) pagerState.currentPage + 1 else pagerState.currentPage - 1
                                            pagerState.animateScrollToPage(
                                                target.coerceIn(
                                                    0,
                                                    pagerState.pageCount - 1
                                                )
                                            )
                                        }
                                    }
                                }
                                // 无论按下还是松开，全部 return true 强行吞掉事件，系统音量条就不会出来了
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .transformable(transformableState)
                    .let {
                        if (isVerticalMode && globalScale.value > 1f) {
                            it.draggable(draggableState, Orientation.Horizontal)
                        } else it
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
                                            val targetScale = 1.65f
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
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(imageUrls, key = { index, _ -> index }) { _, imgUrl ->
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
                        }
                    }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 16.dp
                        ) { page ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
                                    // 注入稳定的 onClick 回调
                                    onClick = horizontalPagerClick
                                )
                            }
                        }
                    }
                }
            }

            // 顶部栏
            AnimatedVisibility(
                visible = showUi && !showChapterList,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        // 【拦截穿透】防止点击状态栏的空白处意外触发底层图片事件
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
                        Text("回到原帖", color = YamiboColors.secondary)
                    }
                }
            }

            // 底部栏
            AnimatedVisibility(
                visible = showUi && !showChapterList,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        // 【拦截穿透】
                        .pointerInput(Unit) { detectTapGestures {} }
                        .navigationBarsPadding()
                ) {
                    if (showSettingsPanel) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("阅读方向", color = Color.White, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                val modes = listOf("下滑翻页", "左滑翻页", "右滑翻页")
                                modes.forEachIndexed { index, title ->
                                    Button(
                                        onClick = {
                                            val targetPage = currentIndex
                                            MangaSettings.saveReadMode(context, index)
                                            readMode = index

                                            scope.launch {
                                                if (index == 0) lazyListState.scrollToItem(
                                                    targetPage
                                                )
                                                else pagerState.scrollToPage(targetPage)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (readMode == index) YamiboColors.secondary else Color.DarkGray
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            title,
                                            color = if (readMode == index) Color.Black else Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = { showSettingsPanel = false },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("完成", color = YamiboColors.secondary, fontSize = 16.sp)
                            }
                        }
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (imageUrls.size > 1) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${currentIndex + 1}",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
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
                                            .padding(horizontal = 12.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = YamiboColors.secondary,
                                            activeTrackColor = YamiboColors.secondary,
                                            inactiveTrackColor = Color.DarkGray
                                        )
                                    )
                                    Text("${imageUrls.size}", color = Color.White, fontSize = 12.sp)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = { showChapterList = true }) {
                                    Text("目录", color = Color.White, fontSize = 16.sp)
                                }
                                TextButton(onClick = { showSettingsPanel = true }) {
                                    Text("设置", color = Color.White, fontSize = 16.sp)
                                }
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
                    onDismiss = { showChapterList = false },
                    onTitleEdit = { newTitle ->
                        mangaDirVM.renameDirectory(newTitle)
                    },
                    onChapterClick = { chapter ->
                        showChapterList = false
                        if (chapter.url.isNotEmpty()) {
                            val encodedChapterUrl = java.net.URLEncoder.encode(chapter.url, "utf-8")
                            val encodedOriginalUrl =
                                java.net.URLEncoder.encode(originalUrl, "utf-8")
                            navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false&initialPage=0") {
                                popUpTo("FavoritePage") { inclusive = false }
                            }
                        }
                    }
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