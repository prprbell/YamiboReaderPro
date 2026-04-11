package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import org.shirakawatyu.yamibo.novel.util.WebViewPool

private val hideCommand = """
    javascript:(function() {
        var style = document.createElement('style');
        style.innerHTML = '.my, .mz { visibility: hidden !important; pointer-events: none !important; }';
        document.head.appendChild(style);
    })()
""".trimIndent()

class FullscreenApiManga {
    var onStateChange: ((Boolean) -> Unit)? = null
    var onMangaActionDone: (() -> Unit)? = null

    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post { onStateChange?.invoke(isFullscreen) }
    }

    @JavascriptInterface
    fun notifyMangaActionDone() {
        Handler(Looper.getMainLooper()).post { onMangaActionDone?.invoke() }
    }
}

class MangaWebNativeJSInterface {
    var onTriggerManga: ((String, Int, String) -> Unit)? = null
    private var lastNavTime = 0L

    @JavascriptInterface
    fun openNativeManga(urlsJoined: String, clickedIndex: Int, title: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNavTime < 1000) return
        lastNavTime = currentTime

        Handler(Looper.getMainLooper()).post {
            onTriggerManga?.invoke(urlsJoined, clickedIndex, title)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MangaWebPage(
    url: String,
    navController: NavController,
    webChromeClient: WebChromeClient,
    originalFavoriteUrl: String = url,
    isFastForward: Boolean = false,
    initialPage: Int = 0
) {

    val finalUrl = remember(url) {
        if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
    }
    var canGoBack by remember { mutableStateOf(false) }
    var baseIndex by remember { mutableIntStateOf(-1) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf<String?>(null) }

    var autoOpenMangaMode by rememberSaveable { mutableStateOf(!isFastForward) }
    var isWaitingForNativeReturn by rememberSaveable { mutableStateOf(isFastForward) }

    val showBlackScreen = autoOpenMangaMode || isWaitingForNativeReturn
    val currentAutoOpenMode by rememberUpdatedState(autoOpenMangaMode)

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
    val bottomNavBarVM: BottomNavBarVM = viewModel(viewModelStoreOwner = context)

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

        CookieManager.getInstance().setCookie(loadUrl, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

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

    val isFullscreenState = remember { mutableStateOf(false) }

    val fullscreenApi = remember { FullscreenApiManga() }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }
    val nativeMangaApi = remember { MangaWebNativeJSInterface() }
    var initialLoadTriggered by remember { mutableStateOf(false) }
    val mangaWebView = remember {
        WebViewPool.acquire(context).apply {
            settings.apply {
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true
            }
            addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            this.webChromeClient = webChromeClient
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (isWaitingForNativeReturn) {
                    isWaitingForNativeReturn = false
                }
                mangaWebView.onResume()
                val window = activity?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                        false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(showBlackScreen) {
        mangaWebView.settings.apply {
            blockNetworkImage = showBlackScreen
            loadsImagesAutomatically = !showBlackScreen
        }
    }
    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        mangaWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
            val cleanHtml = try {
                com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
            } catch (_: Exception) {
                htmlResult?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
            }

            val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
            GlobalData.tempMangaUrls = urls

            val targetIndex = if (initialPage > 0 && clickedIndex == 0) initialPage else clickedIndex
            GlobalData.tempMangaIndex =
                targetIndex.coerceIn(0, maxOf(0, urls.size - 1))

            GlobalData.tempHtml = cleanHtml
            GlobalData.tempTitle = title

            mangaWebView.evaluateJavascript("window.stop();", null)
            mangaWebView.stopLoading()
            mangaWebView.onPause()

            autoOpenMangaMode = false
            isWaitingForNativeReturn = true

            val passUrl = currentUrl ?: "https://bbs.yamibo.com/forum.php"

            val encodedUrl = java.net.URLEncoder.encode(passUrl, "utf-8")
            val encodedOriginal = java.net.URLEncoder.encode(originalFavoriteUrl, "utf-8")
            navController.navigate("NativeMangaPage?url=$encodedUrl&originalUrl=$encodedOriginal")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

    ActivityWebViewLifecycleObserver(mangaWebView)
    LaunchedEffect(isFullscreenState.value) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val shouldBeFullscreen = isFullscreenState.value

        if (shouldBeFullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bottomNavBarVM.setBottomNavBarVisibility(false)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = false
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

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
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false
            currentUrl?.let { threadUrl ->
                if (MangaTitleCleaner.extractTidFromUrl(threadUrl) != null) {
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
                        val sectionName = try {
                            com.alibaba.fastjson2.JSON.parse(result) as? String ?: ""
                        } catch (_: Exception) {
                            result?.replace("\"", "") ?: ""
                        }

                        val allowedSections = listOf(
                            "中文百合漫画区",
                            "貼圖區",
                            "贴图区",
                            "原创图作区",
                            "百合漫画图源区"
                        )

                        val isCrossForum = sectionName.isNotBlank() && allowedSections.none {
                            sectionName.contains(it)
                        }

                        if (!isCrossForum) {
                            val pageTitle = mangaWebView.title ?: ""
                            mangaWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                scope.launch(Dispatchers.Default) {
                                    val cleanHtml = try {
                                        com.alibaba.fastjson2.JSON.parse(htmlResult) as? String
                                            ?: ""
                                    } catch (_: Exception) {
                                        htmlResult
                                    }
                                    if (cleanHtml.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            mangaDirVM.initDirectoryFromWeb(
                                                threadUrl,
                                                cleanHtml,
                                                pageTitle
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    var isHistoryCleared by remember { mutableStateOf(false) }

    LaunchedEffect(mangaWebView) {
        mangaWebView.webViewClient = object : YamiboWebViewClient() {
            override fun onFormResubmission(
                view: WebView?,
                dontResend: android.os.Message?,
                resend: android.os.Message?
            ) {
                resend?.sendToTarget()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                view?.let { WebViewPool.discard(it) }
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                showLoadError = true
                return true
            }

            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, pageUrl, favicon)

                if (url == "about:blank" || url.contains("warmup=true")) return
                GlobalData.webProgress.value = 0
                isLoading = true
                currentUrl = pageUrl

                if (view != null) {
                    val list = view.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                }

                view?.loadUrl(hideCommand)
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                historyUrl: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, historyUrl, isReload)
                if (view != null) {
                    val list = view.copyBackForwardList()
                    if (baseIndex == -1) {
                        baseIndex = list.currentIndex
                    } else if (list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                }
            }

            override fun onPageCommitVisible(view: WebView?, commitUrl: String?) {
                if (commitUrl == "about:blank" || commitUrl?.contains("warmup=true") == true) return
                super.onPageCommitVisible(view, commitUrl)
                if (isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    showLoadError = false
                }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)

                if (finishedUrl == "about:blank") return

                if (!isHistoryCleared) {
                    view?.clearHistory()
                    isHistoryCleared = true
                }

                isLoading = false
                currentUrl = finishedUrl

                canGoBack = view?.let {
                    val list = it.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    baseIndex != -1 && list.currentIndex > baseIndex
                } ?: false

                if (mangaWebView.url == null) {
                    val finalUrl =
                        if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
                    mangaWebView.loadUrl(finalUrl)
                }

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
                    if (result == "true" && !currentAutoOpenMode) {
                        val injectJs = """
                            javascript:(function() {
                                document.addEventListener('click', function(e) {
                                    var targetContainer = e.target.closest('.img_one li, .img_one a, .message a, .img_one img, .message img');
                                    if (!targetContainer) return;
                                    
                                    var targetImg = targetContainer.tagName.toLowerCase() === 'img' ? targetContainer : targetContainer.querySelector('img');
                                    
                                    if (targetImg) {
                                        var imgSrc = targetImg.getAttribute('src') || '';
                                        var imgZsrc = targetImg.getAttribute('zsrc') || '';
                                        
                                        if (imgSrc.indexOf('smiley') === -1 && imgZsrc.indexOf('smiley') === -1) { 
                                            e.preventDefault(); 
                                            e.stopPropagation();
                                            
                                            var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                                            var urls = [];
                                            var clickedIndex = 0;
                                            for (var i = 0; i < allImgs.length; i++) {
                                                var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('file') || allImgs[i].getAttribute('src');
                                                if (rawSrc) {
                                                    var absoluteUrl = new URL(rawSrc, document.baseURI).href;
                                                    urls.push(absoluteUrl);
                                                    if (allImgs[i] === targetImg) clickedIndex = urls.length - 1;
                                                }
                                            }
                                            if (window.NativeMangaApi) {
                                                window.NativeMangaApi.openNativeManga(urls.join('|||'), clickedIndex, document.title);
                                            }
                                        }
                                    }
                                }, true); 
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(injectJs, null)
                    }
                }
            }

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

        if (!initialLoadTriggered) {
            initialLoadTriggered = true
            startLoading(mangaWebView, finalUrl)
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            val clickJs = """
                (function() {
                    // 版块与公告检查
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

                    // 提取图片
                    function extractAndOpenNative() {
                        if (!window.NativeMangaApi) return false;
                        
                        var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                        if (allImgs.length === 0) return false;
                        
                        var urls = [];
                        for (var i = 0; i < allImgs.length; i++) {
                            var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                            if (rawSrc) urls.push(new URL(rawSrc, document.baseURI).href);
                        }
                        
                        if (urls.length > 0) {
                            window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.title);
                            return true;
                        }
                        return false;
                    }

                    if (extractAndOpenNative()) {
                        return; // 如果第一次就成功了，直接结束
                    }

                    var extractAttempts = 0;
                    var maxExtracts = 10;
                    
                    var extractTimer = setInterval(function() {
                        extractAttempts++;
                        
                        if (extractAndOpenNative()) {
                            clearInterval(extractTimer);
                            return;
                        }
                        
                        if (extractAttempts >= maxExtracts) {
                            clearInterval(extractTimer);
                            if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                                window.AndroidFullscreen.notifyMangaActionDone();
                            }
                        }
                    }, 250);

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

    val performExit = {
        bottomNavBarVM.setBottomNavBarVisibility(true)
        view.post { navController.navigateUp() }
    }
    BackHandler(enabled = true) {
        if (canGoBack) {
            mangaWebView.goBack()
        } else {
            performExit()
        }
    }
    LaunchedEffect(mangaDirVM.currentDirectory, currentUrl, isLoading) {
        if (isLoading) return@LaunchedEffect

        val dir = mangaDirVM.currentDirectory ?: return@LaunchedEffect
        val url = currentUrl ?: return@LaunchedEffect
        val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: return@LaunchedEffect

        val currentChapter = dir.chapters.find { it.tid == tid }
        if (currentChapter != null) {
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
                val sectionName = try {
                    com.alibaba.fastjson2.JSON.parse(result) as? String ?: ""
                } catch (_: Exception) {
                    result?.replace("\"", "") ?: ""
                }

                val allowedSections =
                    listOf("中文百合漫画区", "貼圖區", "贴图区", "原创图作区", "百合漫画图源区")

                val isCrossForum =
                    sectionName.isNotBlank() && allowedSections.none { sectionName.contains(it) }

                if (!isCrossForum) {
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
                    Log.w("MangaWebPage", "已拦截跨区漫画书签写入！当前版块：${sectionName}")
                }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(YamiboColors.primary)
                .align(Alignment.TopCenter)
                .zIndex(1f)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AndroidView(
                factory = {
                    (mangaWebView.parent as? ViewGroup)?.removeView(mangaWebView)
                    mangaWebView
                },
                update = {
                    it.onResume()
                    it.resumeTimers()

                    val list = it.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                    currentUrl = it.url
                },
                onRelease = { webView ->
                    timeoutJob?.cancel()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.apply {
                        removeJavascriptInterface("AndroidFullscreen")
                        removeJavascriptInterface("NativeMangaApi")
                    }
                    WebViewPool.release(webView)
                }
            )

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
        }
        AnimatedVisibility(
            visible = showBlackScreen,
            enter = fadeIn(tween(0)),
            exit = fadeOut(tween(100)),
            modifier = Modifier.zIndex(2f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures { }; detectVerticalDragGestures { _, _ -> } }
            ) {
                if (autoOpenMangaMode) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }
    }
}