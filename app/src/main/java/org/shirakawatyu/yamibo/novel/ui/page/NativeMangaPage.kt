package org.shirakawatyu.yamibo.novel.ui.page

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
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
    url: String, // 原帖 URL，用于目录匹配和进度保存
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

    // 修复切换屏幕或切后台状态丢失导致的白屏
    var imageUrls by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var initialIndex by rememberSaveable { mutableIntStateOf(0) }
    var showUi by rememberSaveable { mutableStateOf(true) }
    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var isVerticalMode by rememberSaveable { mutableStateOf(true) }

    // ====== 终极版：精准锚点缩放与平移状态 ======
    val globalScale = remember { Animatable(1f) }
    val globalOffsetX = remember { Animatable(0f) }
    val globalOffsetY = remember { Animatable(0f) } // 新增：垂直偏移追踪

    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }

    // 1. 双指手势：以屏幕中心为原点，等比例缩放平移量
    val transformableState =
        rememberTransformableState { zoomChange, panChange, _ ->
            if (isVerticalMode) {
                scope.launch {
                    val oldScale = globalScale.value
                    val dampenedZoom = 1f + (zoomChange - 1f) * 0.4f // 阻尼，防止模拟器缩放过快
                    val newScale = (oldScale * dampenedZoom).coerceIn(1f, 4f)

                    val scaleDelta = newScale / oldScale
                    var newOffsetX = globalOffsetX.value * scaleDelta + panChange.x
                    var newOffsetY = globalOffsetY.value * scaleDelta + panChange.y

                    // 动态计算平移边界
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

    // 初始化提取数据
    LaunchedEffect(Unit) {
        if (GlobalData.tempMangaUrls.isNotEmpty()) {
            imageUrls = GlobalData.tempMangaUrls
            initialIndex = GlobalData.tempMangaIndex
            if (GlobalData.tempHtml.isNotBlank()) {
                mangaDirVM.initDirectoryFromWeb(url, GlobalData.tempHtml, GlobalData.tempTitle)
            }
            // 清空全局变量
            GlobalData.tempMangaUrls = emptyList()
            GlobalData.tempHtml = ""
        }
    }

    // 沉浸式与退出逻辑
    val originalStatusBarColor =
        remember { mutableIntStateOf(activity?.window?.statusBarColor ?: 0) }
    val performExit = {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = originalStatusBarColor.intValue
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)
        navController.navigateUp() // 返回上一个页面
        Unit
    }
    BackHandler { performExit() }

    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.BLACK
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        bottomNavBarVM.setBottomNavBarVisibility(false)
        onDispose { }
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
            // ====== 预加载策略 (双向缓存) ======
            val imageLoader = context.imageLoader
            LaunchedEffect(currentIndex, imageUrls) {
                if (imageUrls.isEmpty()) return@LaunchedEffect

                // 策略：预加载前面 2 张（保证上滑不卡），预加载后面 3 张（保证下滑不卡）
                val prefetchRange = (currentIndex - 2)..(currentIndex + 3)
                for (i in prefetchRange) {
                    if (i in imageUrls.indices) {
                        // 静默构建请求并推入 Coil 的加载队列
                        val request = ImageRequest.Builder(context)
                            .data(imageUrls[i])
                            .addHeader("Cookie", cookie)
                            .addHeader("Referer", "https://bbs.yamibo.com/")
                            .memoryCachePolicy(CachePolicy.ENABLED) // 强制进入内存
                            .diskCachePolicy(CachePolicy.ENABLED)   // 强制进入磁盘
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
            // 保存书签
            LaunchedEffect(currentIndex) {
                val currentTid = MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect
                val chapter = mangaDirVM.currentDirectory?.chapters?.find { it.tid == currentTid }
                if (chapter != null) {
                    val shortTitle =
                        if (chapter.chapterNum % 1f == 0f) "读至第 ${chapter.chapterNum.toInt()} 话 - ${currentIndex + 1}页" else "读至第 ${chapter.chapterNum} 话 - ${currentIndex + 1}页"
                    favoriteVM.updateMangaProgress(url, url, shortTitle)
                }
            }

            // 图片渲染区
            // 图片渲染区
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformableState)
                    .draggable(draggableState, Orientation.Horizontal) // 注入单指水平平移
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (isVerticalMode) {
                                    scope.launch {
                                        if (globalScale.value > 1f) {
                                            // 缩小回原状
                                            launch { globalScale.animateTo(1f, tween(300)) }
                                            launch { globalOffsetX.animateTo(0f, tween(300)) }
                                            launch { globalOffsetY.animateTo(0f, tween(300)) }
                                        } else {
                                            // 核心数学：双击放大时，精准拉近到手指点击的位置！
                                            val targetScale = 2f
                                            val centerPx =
                                                Offset(screenWidthPx / 2f, screenHeightPx / 2f)

                                            // 计算偏移量，使点击点移动到屏幕视觉中心
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
                                }
                            },
                            onTap = { showUi = !showUi }
                        )
                    }
            ) {
                if (isVerticalMode) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            // 关键改变：把缩放交给了 GPU 渲染层，不再暴力修改控件物理尺寸！
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
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imgUrl)
                                    .addHeader("Cookie", cookie)
                                    .addHeader("Referer", "https://bbs.yamibo.com/")
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .crossfade(false)
                                    .build(),
                                contentDescription = null,
                                // 恢复为最普通的 fillMaxWidth，布局计算零压力
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                } else {
                    // 横屏(翻页模式)保留 ZoomableAsyncImage，因为横屏确实需要每页独立缩放
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 16.dp
                    ) { page ->
                        ZoomableAsyncImage(
                            model = ImageRequest.Builder(context).data(imageUrls[page])
                                .addHeader("Cookie", cookie)
                                .addHeader("Referer", "https://bbs.yamibo.com/").build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // 顶部栏：返回 + 标题 + 回到原帖
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
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = performExit) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = Color.White
                        )
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
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = performExit) {
                        Text(
                            "回到原帖",
                            color = YamiboColors.secondary
                        )
                    }
                }
            }

            // 底部栏：进度条 + 目录 + 设置
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
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (imageUrls.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${currentIndex + 1}", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = currentIndex.toFloat(),
                                valueRange = 0f..(imageUrls.size - 1).toFloat(),
                                onValueChange = { target ->
                                    scope.launch {
                                        if (isVerticalMode) lazyListState.scrollToItem(
                                            target.toInt()
                                        ) else pagerState.scrollToPage(target.toInt())
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
                            Text(
                                "目录",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                        TextButton(onClick = { showSettingsDialog = true }) {
                            Text(
                                "设置",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            // 目录与设置弹窗组件 (复用你的 MangaChapterPanel)
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
                    onChapterClick = { chapter ->
                        showChapterList = false
                        if (chapter.url.isNotEmpty()) {
                            val encodedChapterUrl = java.net.URLEncoder.encode(chapter.url, "utf-8")
                            val encodedOriginalUrl = java.net.URLEncoder.encode(url, "utf-8")

                            navController.navigate("MangaWebPage/$encodedChapterUrl/$encodedOriginalUrl?fastForward=false") {
                                popUpTo("FavoritePage") { inclusive = false }
                            }
                        }
                    }
                )
            }
            if (showSettingsDialog) {
                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    title = { Text("阅读设置", fontSize = 18.sp) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("阅读方向", fontSize = 16.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("横屏", fontSize = 14.sp)
                                Switch(
                                    checked = isVerticalMode,
                                    onCheckedChange = { isVerticalMode = it },
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                Text("竖屏", fontSize = 14.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showSettingsDialog = false
                        }) { Text("完成") }
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