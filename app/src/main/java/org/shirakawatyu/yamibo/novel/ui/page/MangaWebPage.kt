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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner

private val hideCommand = """
    javascript:(function() {
        var style = document.createElement('style');
        style.innerHTML = '.my, .mz { visibility: hidden !important; pointer-events: none !important; }';
        document.head.appendChild(style);
    })()
""".trimIndent()

class FullscreenApiManga(
    private val onStateChange: ((Boolean) -> Unit)?,
    private val onMangaActionDone: (() -> Unit)?
) {
    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post { onStateChange?.invoke(isFullscreen) }
    }

    @JavascriptInterface
    fun notifyMangaActionDone() {
        Handler(Looper.getMainLooper()).post { onMangaActionDone?.invoke() }
    }
}

class MangaWebNativeJSInterface(
    private val navController: NavController,
    private val getCurrentUrl: () -> String?,
    private val onNavigateStart: () -> Unit // 改名，语义变为：准备跳转原生
) {
    @JavascriptInterface
    fun openNativeManga(urlsJoined: String, clickedIndex: Int, html: String, title: String) {
        val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
        Handler(Looper.getMainLooper()).post {
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempMangaUrls = urls
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempMangaIndex = clickedIndex
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempHtml = html
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempTitle = title

            // 1. 触发回调，告诉 MangaWebPage：“我要跳了，你给我保持黑屏别动！”
            onNavigateStart()

            // 2. 直接发起跳转
            val passUrl = getCurrentUrl() ?: "https://bbs.yamibo.com/forum.php"
            val encodedUrl = java.net.URLEncoder.encode(passUrl, "utf-8")
            navController.navigate("NativeMangaPage?url=$encodedUrl")
        }
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
    originalFavoriteUrl: String = url,
    isFastForward: Boolean = false
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
    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    // 1. 负责控制是否执行自动探测 JS
    var autoOpenMangaMode by rememberSaveable { mutableStateOf(!isFastForward) }
    // 2. 负责在原生阅读器跳回时，暂时维持黑屏掩护（避免闪现网页）
    var isWaitingForNativeReturn by rememberSaveable { mutableStateOf(false) }

    // 3. 最终决定是否显示黑屏的聚合状态
    val showBlackScreen = autoOpenMangaMode || isWaitingForNativeReturn

    // 生命周期监听。当从原生阅读器返回此页面时，撤销黑屏掩护
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // 当页面回到前台时 (ON_RESUME)，解除掩护
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (isWaitingForNativeReturn) {
                    isWaitingForNativeReturn = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    if (!isExiting) {
        SetStatusBarColor(if (showBlackScreen || isFullscreenState.value) Color.Black else YamiboColors.primary)
    }
    DisposableEffect(Unit) {
        onDispose {
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
            addJavascriptInterface(
                FullscreenApiManga(
                    onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen },
                    onMangaActionDone = { autoOpenMangaMode = false }
                ),
                "AndroidFullscreen"
            )
            this.webChromeClient = webChromeClient
        }
    }
    ActivityWebViewLifecycleObserver(mangaWebView)
    // 1. 系统 UI 显隐控制
    LaunchedEffect(isFullscreenState.value, autoOpenMangaMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val shouldBeFullscreen = isFullscreenState.value || showBlackScreen

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
        }
    }

    // 2. 处理大图模式逻辑与版块探测
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            mangaWebView.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                    window.pswpObserverAttached = false;
                })();
                """.trimIndent(),
                null
            )
            pendingNavigateUrl?.let { navigateUrl ->
                isLoading = true
                mangaWebView.loadUrl(navigateUrl)
                pendingNavigateUrl = null
            }
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false

            // 解析目录
            currentUrl?.let { threadUrl ->
                if (MangaTitleCleaner.extractTidFromUrl(threadUrl) != null) {
                    // 注入 JS 探测当前页面的版块名称
                    val checkSectionJs = """
                        (function() {
                            var sectionHeader = document.querySelector('.header h2 a');
                            if (sectionHeader) return sectionHeader.innerText.trim();
                            var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
                            if (nav) return nav.innerText.trim();
                            return '';
                        })();
                    """.trimIndent()

                    mangaWebView.evaluateJavascript(checkSectionJs) { result ->
                        // 解析 JS 返回的字符串
                        val sectionName = try {
                            com.alibaba.fastjson2.JSON.parse(result) as? String ?: ""
                        } catch (e: Exception) {
                            result?.replace("\"", "") ?: ""
                        }

                        // 允许的白名单
                        val allowedSections = listOf(
                            "中文百合漫画区",
                            "貼圖區",
                            "贴图区",
                            "原创图作区",
                            "百合漫画图源区"
                        )

                        // 判断条件：如果抓到了非空的版块名，且不在白名单内，说明是跨区帖
                        val isCrossForum = sectionName.isNotBlank() && allowedSections.none {
                            sectionName.contains(it)
                        }

                        if (!isCrossForum) {
                            // 只有在合法的漫画/图区内，才抓取庞大的网页源码并初始化目录
                            val pageTitle = mangaWebView.title ?: ""
                            mangaWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                val cleanHtml = try {
                                    com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
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

                // 探测漫画版块，并注入拦截器（拦截手动点击）
                view?.evaluateJavascript(
                    """
                    (function(){
                        var a = document.querySelector('.header h2 a');
                        if (!a) return false;
                        var t = a.innerText;
                        return t.indexOf('中文百合漫画区') !== -1 || t.indexOf('貼圖區') !== -1 || t.indexOf('原创图作区') !== -1 || t.indexOf('百合漫画图源区') !== -1;
                    })()
                    """.trimIndent()
                ) { result ->
                    if (result == "true") {
                        val injectJs = """
                            javascript:(function() {
                                document.addEventListener('click', function(e) {
                                    var targetImg = e.target.closest('.img_one img, .message img');
                                    if (targetImg && targetImg.src.indexOf('smiley') === -1) { 
                                        e.preventDefault(); 
                                        e.stopPropagation();
                                        
                                        var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                                        var urls = [];
                                        var clickedIndex = 0;
                                        for (var i = 0; i < allImgs.length; i++) {
                                            var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                                            if (rawSrc) {
                                                var absoluteUrl = new URL(rawSrc, document.baseURI).href;
                                                urls.push(absoluteUrl);
                                                if (allImgs[i] === targetImg) clickedIndex = urls.length - 1;
                                            }
                                        }
                                        if (window.NativeMangaApi) {
                                            var html = document.documentElement.outerHTML;
                                            window.NativeMangaApi.openNativeManga(urls.join('|||'), clickedIndex, html, document.title);
                                        }
                                    }
                                }, true); 
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(injectJs, null)
                    }
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
                        var allowedSections = ['中文百合漫画区', '貼圖區', '贴图区', '原创图作区', '百合漫画图源区'];
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

                    if (window.NativeMangaApi) {
                        var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                        var urls = [];
                        for (var i = 0; i < allImgs.length; i++) {
                            var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                            if (rawSrc) urls.push(new URL(rawSrc, document.baseURI).href);
                        }
                        if (urls.length > 0) {
                            window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.documentElement.outerHTML, document.title);
                            return;
                        }
                    }
                    
                    // 兜底策略：如果没图，取消黑屏
                    if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                        window.AndroidFullscreen.notifyMangaActionDone();
                    }
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
        performExit()
    }
    LaunchedEffect(mangaDirVM.currentDirectory, currentUrl, isLoading) {
        if (isLoading) return@LaunchedEffect

        val dir = mangaDirVM.currentDirectory ?: return@LaunchedEffect
        val url = currentUrl ?: return@LaunchedEffect
        val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect

        // 在目录中查找当前页面 TID 对应的章节
        val currentChapter = dir.chapters.find { it.tid == tid }
        if (currentChapter != null) {
            // 【新增】：注入 JS 探测面包屑导航中的版块名称
            val checkSectionJs = """
                (function() {
                    var sectionHeader = document.querySelector('.header h2 a');
                    if (sectionHeader) return sectionHeader.innerText.trim();
                    var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
                    if (nav) return nav.innerText.trim();
                    return '';
                })();
            """.trimIndent()

            mangaWebView.evaluateJavascript(checkSectionJs) { result ->
                // 解析 JS 返回的字符串
                val sectionName = try {
                    com.alibaba.fastjson2.JSON.parse(result) as? String ?: ""
                } catch (e: Exception) {
                    result?.replace("\"", "") ?: ""
                }

                // 允许的白名单（添加了简体的贴图区做容错）
                val allowedSections =
                    listOf("中文百合漫画区", "貼圖區", "贴图区", "原创图作区", "百合漫画图源区")

                // 判断条件：如果抓到了非空的版块名，且不在白名单内，说明是跨区帖
                val isCrossForum =
                    sectionName.isNotBlank() && allowedSections.none { sectionName.contains(it) }

                if (!isCrossForum) {
                    // 安全的漫画区帖子，正常更新进度
                    val shortTitle = when {
                        currentChapter.chapterNum >= 1000f -> "番外"
                        currentChapter.chapterNum % 1f == 0f -> "读至第 ${currentChapter.chapterNum.toInt()} 话"
                        else -> "读至第 ${currentChapter.chapterNum} 话"
                    }

                    favoriteVM.updateMangaProgress(
                        favoriteUrl = originalFavoriteUrl,
                        chapterUrl = url,
                        chapterTitle = shortTitle
                    )
                } else {
                    // 拦截跨区帖子的书签记录
                    Log.w("MangaWebPage", "已拦截跨区漫画书签写入！当前版块：${sectionName}")
                }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (mangaWebView.parent as? ViewGroup)?.removeView(mangaWebView)
                val nativeApi = MangaWebNativeJSInterface(
                    navController = navController,
                    getCurrentUrl = { currentUrl },
                    onNavigateStart = {
                        autoOpenMangaMode = false
                        isWaitingForNativeReturn = true
                    })
                mangaWebView.addJavascriptInterface(nativeApi, "NativeMangaApi")
                mangaWebView
            },
            update = {
                canGoBack = it.canGoBack()
                currentUrl = it.url
            },
            onRelease = {
                timeoutJob?.cancel()
                it.apply {
                    onPause()
                    stopLoading()
                    webViewClient = android.webkit.WebViewClient()
                    setWebChromeClient(null) // <--- 改用这种写法
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
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

        if (showBlackScreen) {
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