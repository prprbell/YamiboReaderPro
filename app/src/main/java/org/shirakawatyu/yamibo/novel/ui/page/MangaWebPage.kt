package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner

private val hideCommand = """
    javascript:(function() {
        var style = document.createElement('style');
        style.innerHTML = '.my, .mz { visibility: hidden !important; pointer-events: none !important; }';
        document.head.appendChild(style);
    })()
""".trimIndent()

object FullscreenApiManga {
    var onStateChange: ((Boolean) -> Unit)? = null
    var onUiStateChange: ((Boolean) -> Unit)? = null
    var onMangaActionDone: (() -> Unit)? = null
    var onImageProgressChange: ((Int, Int) -> Unit)? = null

    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post { onStateChange?.invoke(isFullscreen) }
    }

    @JavascriptInterface
    fun notifyUi(isUiVisible: Boolean) {
        Handler(Looper.getMainLooper()).post { onUiStateChange?.invoke(isUiVisible) }
    }

    @JavascriptInterface
    fun notifyMangaActionDone() {
        Handler(Looper.getMainLooper()).post { onMangaActionDone?.invoke() }
    }

    @JavascriptInterface
    fun updateImageProgress(current: Int, total: Int) {
        Handler(Looper.getMainLooper()).post { onImageProgressChange?.invoke(current, total) }
    }
}

/**
 * 漫画阅读页面，进入时自动开启大图模式，支持目录展示和图片进度滑动。
 *
 * @param url 帖子的初始 URL
 * @param navController 导航控制器
 * @param webChromeClient 共享的 WebChromeClient
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MangaWebPage(
    url: String,
    navController: NavController,
    webChromeClient: WebChromeClient,
    originalFavoriteUrl: String = url
) {

    val finalUrl = remember(url) {
        if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
    }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var showChapterList by remember { mutableStateOf(false) }
    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    // 漫画页默认开启进入大图的过渡模式
    var autoOpenMangaMode by remember { mutableStateOf(true) }
    var currentImageIndex by remember { mutableFloatStateOf(1f) }
    var totalImageCount by remember { mutableFloatStateOf(1f) }

    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )

    val context = LocalContext.current
    val activity = context as? Activity
    val favoriteVM: FavoriteVM = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )
    val view = LocalView.current
    // 1. 新增：在页面刚组合时，瞬间记住上一页（收藏页）的黄色和亮色图标状态
    val originalStatusBarColor =
        remember { mutableIntStateOf(activity?.window?.statusBarColor ?: 0) }
    val originalLightStatusBars = remember {
        mutableStateOf(activity?.window?.let {
            WindowCompat.getInsetsController(
                it,
                view
            ).isAppearanceLightStatusBars
        } ?: false)
    }
    // 新增：标记是否正在退出
    var isExiting by remember { mutableStateOf(false) }
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context as ComponentActivity)

    fun runTimeout(webView: WebView, onTimeout: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(10000)
            if (isLoading) {
                onTimeout()
            }
        }
    }

    val startLoading: (webView: WebView, loadUrl: String) -> Unit = { webView, loadUrl ->
        isLoading = true
        showLoadError = false
        retryCount = 0

        runTimeout(webView) {
            Log.w("MangaWebPage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                Log.e("MangaWebPage", "Retry timed out. Giving up.")
                isLoading = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.reload()
        }
        webView.loadUrl(loadUrl)
    }

    // ----- 全屏状态控制 -----
    val isFullscreenState = remember { mutableStateOf(false) }
    val isFullscreenUiVisible = remember { mutableStateOf(true) }
    if (!isExiting) {
        SetStatusBarColor(if (autoOpenMangaMode || isFullscreenState.value) Color.Black else YamiboColors.primary)
    }
    DisposableEffect(Unit) {
        FullscreenApiManga.onStateChange = { isFullscreen ->
            isFullscreenState.value = isFullscreen
            if (!isFullscreen) isFullscreenUiVisible.value = true
        }
        FullscreenApiManga.onUiStateChange = { isUiVisible ->
            isFullscreenUiVisible.value = isUiVisible
        }
        FullscreenApiManga.onMangaActionDone = {
            autoOpenMangaMode = false
        }
        FullscreenApiManga.onImageProgressChange = { current, total ->
            currentImageIndex = current.toFloat()
            totalImageCount = total.toFloat()
        }

        onDispose {
            FullscreenApiManga.onStateChange = null
            FullscreenApiManga.onUiStateChange = null
            FullscreenApiManga.onMangaActionDone = null
            FullscreenApiManga.onImageProgressChange = null

            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

    val mangaWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true
            }
            addJavascriptInterface(FullscreenApiManga, "AndroidFullscreen")
            this.webChromeClient = webChromeClient
        }
    }

    // 1. 系统 UI 显隐控制
    LaunchedEffect(isFullscreenState.value, autoOpenMangaMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val shouldBeFullscreen = isFullscreenState.value || autoOpenMangaMode

        if (shouldBeFullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bottomNavBarVM.setBottomNavBarVisibility(false)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            bottomNavBarVM.setBottomNavBarVisibility(true)
            showChapterList = false
        }
    }

    // 2. 处理大图模式逻辑与版块探测
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            mangaWebView.evaluateJavascript(
                "var style = document.getElementById('manga-transition-style'); if (style) style.remove();",
                null
            )
            pendingNavigateUrl?.let { navigateUrl ->
                isLoading = true
                mangaWebView.loadUrl(navigateUrl)
                pendingNavigateUrl = null
            }
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false

            // 注入页码监听 JS
            val observerJs = """
                setTimeout(function() {
                    var counter = document.querySelector('.pswp__counter');
                    if (counter) {
                        var updateProgress = function() {
                            var text = counter.innerText || ''; 
                            var parts = text.split('/');
                            if (parts.length === 2) {
                                var current = parseInt(parts[0].trim());
                                var total = parseInt(parts[1].trim());
                                if (!isNaN(current) && !isNaN(total) && window.AndroidFullscreen && window.AndroidFullscreen.updateImageProgress) {
                                    window.AndroidFullscreen.updateImageProgress(current, total);
                                }
                            }
                        };
                        updateProgress();
                        if (!window.pswpObserverAttached) {
                            var observer = new MutationObserver(updateProgress);
                            observer.observe(counter, { childList: true, characterData: true, subtree: true });
                            window.pswpObserverAttached = true;
                        }
                    }
                }, 500);
            """.trimIndent()
            mangaWebView.evaluateJavascript(observerJs, null)

            // 解析目录
            currentUrl?.let { threadUrl ->
                if (MangaTitleCleaner.extractTidFromUrl(threadUrl) != null) {
                    val pageTitle = mangaWebView.title ?: ""
                    mangaWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                        val cleanHtml = try {
                            JSON.parse(htmlResult) as? String ?: ""
                        } catch (e: Exception) {
                            htmlResult
                        }
                        if (cleanHtml.isNotBlank()) {
                            mangaDirVM.initDirectoryFromWeb(threadUrl, cleanHtml, pageTitle)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(mangaWebView) {
        mangaWebView.webViewClient = object : YamiboWebViewClient() {
            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, pageUrl, favicon)
                isLoading = true
                currentUrl = pageUrl
                canGoBack = view?.canGoBack() ?: false
                view?.loadUrl(hideCommand)
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                historyUrl: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, historyUrl, isReload)
                canGoBack = view?.canGoBack() ?: false
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageCommitVisible(view: WebView?, commitUrl: String?) {
                super.onPageCommitVisible(view, commitUrl)
                if (isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    showLoadError = false
                }
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                isLoading = false
                currentUrl = finishedUrl
                if (mangaWebView.url == null) {
                    val finalUrl =
                        if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
                    mangaWebView.loadUrl(finalUrl)
                }
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    timeoutJob?.cancel()
                    isLoading = false
                    if (retryCount == 0) showLoadError = true
                }
                super.onReceivedError(view, request, error)
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    timeoutJob?.cancel()
                    isLoading = false
                    if (retryCount == 0) showLoadError = true
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }

        // 首次进入加载
        if (mangaWebView.url == null) {
            startLoading(mangaWebView, finalUrl)
        }
    }

    // 1. 监听大图退出状态，执行积压的跳转任务
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            pendingNavigateUrl?.let { navigateUrl ->
                isLoading = true
                mangaWebView.loadUrl(navigateUrl)
                pendingNavigateUrl = null
            }
        } else if (isFullscreenState.value && autoOpenMangaMode) {
            autoOpenMangaMode = false
        }
    }

    // 2. 页面加载完成注入自动点击 JS
    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            val clickJs = """
                (function() {
                    var sectionHeader = document.querySelector('.header h2 a');
                    var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
                    if (sectionName !== '') {
                        var allowedSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
                        var isAllowedSection = false;
                        for (var k = 0; k < allowedSections.length; k++) {
                            if (sectionName.indexOf(allowedSections[k]) !== -1) { isAllowedSection = true; break; }
                        }
                        if (!isAllowedSection) {
                            if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                            return;
                        }
                    }
                    
                    var typeLabel = document.querySelector('.view_tit em');
                    if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                        return; 
                    }
                    
                    if (!document.getElementById('manga-transition-style')) {
                        var style = document.createElement('style');
                        style.id = 'manga-transition-style';
                        style.innerHTML = 'body > *:not(.pswp) { opacity: 0 !important; pointer-events: none !important; } body { background: #000 !important; }';
                        document.head.appendChild(style);
                    }

                    function abortAndNotify() {
                        var style = document.getElementById('manga-transition-style');
                        if (style) style.remove();
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                    }

                    var isDone = false;
                    var attempts = 0;
                    var timer = setInterval(function() {
                        if (isDone) { clearInterval(timer); return; }
                        attempts++;

                        var pswp = document.querySelector('.pswp');
                        if (pswp) {
                            isDone = true;
                            clearInterval(timer);
                            if (window.AndroidFullscreen) {
                                window.AndroidFullscreen.notify(true);
                                window.AndroidFullscreen.notifyMangaActionDone();
                                var counter = document.querySelector('.pswp__counter');
                                if (counter) {
                                    var updateProgress = function() {
                                        var text = counter.innerText || '';
                                        var parts = text.split('/');
                                        if (parts.length === 2) {
                                            var current = parseInt(parts[0].trim());
                                            var total = parseInt(parts[1].trim());
                                            if (!isNaN(current) && !isNaN(total)) window.AndroidFullscreen.updateImageProgress(current, total);
                                        }
                                    };
                                    updateProgress();
                                    var observer = new MutationObserver(updateProgress);
                                    observer.observe(counter, { childList: true, characterData: true, subtree: true });
                                }
                            }
                            return;
                        }

                        var links = document.querySelectorAll('a[data-pswp-width], .img_one a.orange, .message a.orange, .postmessage a.orange');
                        var targetEl = null;
                        for (var i = 0; i < links.length; i++) {
                            var href = links[i].getAttribute('href') || '';
                            var innerHtml = links[i].innerHTML || '';
                            if (href.toLowerCase().indexOf('.gif') === -1 && href.indexOf('static/image/') === -1 && innerHtml.indexOf('static/image/') === -1) {
                                targetEl = links[i]; break;
                            }
                        }
                        
                        if (targetEl) targetEl.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                        if (attempts >= 25) { isDone = true; clearInterval(timer); abortAndNotify(); }
                    }, 200);
                })();
            """.trimIndent()

            mangaWebView.evaluateJavascript(clickJs, null)
            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                mangaWebView.evaluateJavascript(
                    "var style = document.getElementById('manga-transition-style'); if (style) style.remove();",
                    null
                )
            }
        }
    }
    // 3. 完善快速退出逻辑
    val performExit = {
        isExiting = true // 阻止 Compose 继续刷红色
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            WindowCompat.setDecorFitsSystemWindows(window, true)

            // 把状态栏颜色和图标颜色完美恢复成进入前（即 FavoritePage）的样子
            window.statusBarColor = originalStatusBarColor.intValue
            controller.isAppearanceLightStatusBars = originalLightStatusBars.value

            controller.show(WindowInsetsCompat.Type.systemBars())
            bottomNavBarVM.setBottomNavBarVisibility(true)

            view.post { navController.navigateUp() }
        } else {
            bottomNavBarVM.setBottomNavBarVisibility(true)
            navController.navigateUp()
        }
    }
    BackHandler(enabled = true) {
        if (showChapterList) {
            // 如果底部的目录面板开着，先关掉面板
            showChapterList = false
        } else {
            // 无论是否在大图模式，直接退出页面回到收藏界面，不再回退网页历史
            performExit()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { (mangaWebView.parent as? ViewGroup)?.removeView(mangaWebView); mangaWebView },
            update = {
                canGoBack = it.canGoBack()
                currentUrl = it.url
            },
            onRelease = {
                timeoutJob?.cancel()
                it.stopLoading()
                it.destroy()
            }
        )

        // 错误展示
        if (showLoadError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, "加载失败", Modifier.size(48.dp), Color.Gray)
                Spacer(Modifier.height(16.dp))
                Text("页面加载失败", fontSize = 18.sp, color = Color.DarkGray)
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    startLoading(
                        mangaWebView,
                        mangaWebView.url ?: url
                    )
                }) { Text("重试") }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YamiboColors.secondary
            )
        }

        // 全屏 UI (目录和滑动条)
        AnimatedVisibility(
            visible = isFullscreenState.value && isFullscreenUiVisible.value,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                initialOffsetY = { it / 2 }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { showChapterList = true },
                    modifier = Modifier.fillMaxWidth(0.4f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(
                            alpha = 0.6f
                        ), contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Menu, "目录", Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("目录")
                }

                Spacer(Modifier.height(12.dp))

                if (totalImageCount > 1f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("${currentImageIndex.toInt()}", color = Color.White, fontSize = 12.sp)
                        androidx.compose.material3.Slider(
                            value = currentImageIndex,
                            onValueChange = { currentImageIndex = it },
                            onValueChangeFinished = {
                                val targetIndex = currentImageIndex.toInt() - 1
                                val js =
                                    "var pswpObj = window.pswp || window.gallery || (document.querySelector('.pswp') ? document.querySelector('.pswp').PhotoSwipe : null); if (pswpObj && typeof pswpObj.goTo === 'function') pswpObj.goTo($targetIndex);"
                                mangaWebView.evaluateJavascript(js, null)
                            },
                            valueRange = 1f..totalImageCount,
                            steps = if (totalImageCount > 2f) (totalImageCount - 2f).toInt() else 0,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = YamiboColors.secondary.copy(
                                    alpha = 0.8f
                                ),
                                activeTrackColor = YamiboColors.secondary.copy(alpha = 0.5f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f),
                                inactiveTickColor = Color.Transparent,
                                activeTickColor = Color.Transparent
                            )
                        )
                        Text("${totalImageCount.toInt()}", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        if (showChapterList) {
            val currentDir = mangaDirVM.currentDirectory
            val currentTid =
                remember(currentUrl) { currentUrl?.let { MangaTitleCleaner.extractTidFromUrl(it) } }
            val displayChapters = currentDir?.chapters?.map { item ->
                MangaChapter(
                    index = item.chapterNum,
                    title = item.rawTitle,
                    url = item.url,
                    isCurrent = item.tid == currentTid,
                    isRead = false
                )
            } ?: emptyList()

            MangaChapterPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                title = currentDir?.cleanBookName ?: "加载中...",
                chapters = displayChapters,
                isUpdating = mangaDirVM.isUpdatingDirectory,
                cooldownSeconds = mangaDirVM.directoryCooldown,
                strategy = currentDir?.strategy,
                showSearchShortcut = mangaDirVM.showSearchShortcut,
                searchShortcutCountdown = mangaDirVM.searchShortcutCountdown,
                onUpdateClick = { isForced ->
                    mangaDirVM.updateMangaDirectory(isForced)
                },
                onDismiss = { showChapterList = false },
                onChapterClick = { chapter ->
                    showChapterList = false
                    val target = chapter.url
                    if (target.isNotEmpty()) {
                        val absoluteUrl =
                            if (target.startsWith("http")) target else "https://bbs.yamibo.com/$target"

                        // ✅ 修复1：用 originalFavoriteUrl 作为 key，永远能匹配到收藏项
                        // ✅ 修复2：构造简短的"第X话"而非完整帖子标题
                        val shortTitle = when {
                            chapter.index >= 1000f -> "番外"
                            chapter.index % 1f == 0f -> "读至第 ${chapter.index.toInt()} 话"
                            else -> "读至第 ${chapter.index} 话"
                        }
                        favoriteVM.updateMangaProgress(
                            favoriteUrl = originalFavoriteUrl,
                            chapterUrl = absoluteUrl,
                            chapterTitle = shortTitle
                        )

                        autoOpenMangaMode = true
                        pendingNavigateUrl = absoluteUrl
                        mangaWebView.evaluateJavascript("window.history.back();", null)
                    }
                }
            )
        }

        if (autoOpenMangaMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures { }; detectVerticalDragGestures { _, _ -> } }) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }
    }
}