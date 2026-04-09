package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import org.shirakawatyu.yamibo.novel.util.WebViewPool

private val hideCommand = """
    (function() {
        var style = document.createElement('style');
        style.innerHTML = '.nav-search, #nav-more-menu .btn-to-pc { display: none !important; }';
        if (document.head) document.head.appendChild(style);
    })()
""".trimIndent()

class FullscreenApiOther(
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

/**
 * 通用网页展示页面，用于展示未识别或“其他”类型的帖子。
 * 功能与 MinePage 完全相同，增加了页面加载后的版块自动探测逻辑。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OtherWebPage(
    url: String,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    SetStatusBarColor(YamiboColors.primary)
    val finalUrl = remember(url) {
        if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
    }
    var canGoBack by remember { mutableStateOf(false) }
    var baseIndex by remember { mutableIntStateOf(-1) }
    var isLoading by remember { mutableStateOf(true) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    var autoOpenMangaMode by remember { mutableStateOf(false) }
    var isMangaSection by remember { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl)
    }
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )

    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context as ComponentActivity)

    val performExit = {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        bottomNavBarVM.setBottomNavBarVisibility(true)
        view.post { navController.navigateUp() }
    }

    fun runTimeout(webView: WebView, onTimeout: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(10000)
            if (isLoading) {
                onTimeout()
            }
        }
    }

    fun getPagedUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        return if (baseUrl.contains("thread-")) {
            baseUrl.replace(Regex("thread-(\\d+)-(\\d+)"), "thread-$1-$page")
        } else {
            if (baseUrl.contains("page=")) {
                baseUrl.replace(Regex("page=\\d+"), "page=$page")
            } else {
                val sep = if (baseUrl.contains("?")) "&" else "?"
                "$baseUrl${sep}page=$page"
            }
        }
    }

    val startLoading: (webView: WebView, loadUrl: String) -> Unit = { webView, loadUrl ->
        isLoading = true
        showLoadError = false
        retryCount = 0

        runTimeout(webView) {
            Log.w("OtherWebPage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                Log.e("OtherWebPage", "Retry timed out. Giving up.")
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

    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

    val otherWebView = remember {
        WebViewPool.acquire(context).apply {
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true
                loadsImagesAutomatically = true
                blockNetworkImage = false
            }
            addJavascriptInterface(
                FullscreenApiOther(
                    onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen },
                    onMangaActionDone = { autoOpenMangaMode = false }
                ),
                "AndroidFullscreen"
            )
            this.webChromeClient = webChromeClient
        }
    }
    ActivityWebViewLifecycleObserver(otherWebView)
    // 1. 系统 UI 显隐控制
    LaunchedEffect(isFullscreenState.value, autoOpenMangaMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val shouldBeFullscreen = isFullscreenState.value || autoOpenMangaMode

        if (shouldBeFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bottomNavBarVM.setBottomNavBarVisibility(false)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }

    // 2. 处理大图模式逻辑与版块探测
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            otherWebView.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                    window.pswpObserverAttached = false; // 重置标记，确保下次进入能重新绑定
                })();
                """.trimIndent(),
                null
            )
            pendingNavigateUrl?.let { navigateUrl ->
                isLoading = true
                otherWebView.loadUrl(navigateUrl)
                pendingNavigateUrl = null
            }
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false

            currentUrl?.let { threadUrl ->
                if (threadUrl.contains("mod=viewthread") && threadUrl.contains("tid=")) {
                    val checkSectionJs = """
                        (function() {
                            var sectionHeader = document.querySelector('.header h2 a');
                            if (sectionHeader) return sectionHeader.innerText.trim();
                            var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
                            if (nav) return nav.innerText.trim();
                            return '';
                        })();
                    """.trimIndent()

                    otherWebView.evaluateJavascript(checkSectionJs) { result ->
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
                            val pageTitle = otherWebView.title ?: ""
                            otherWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                val cleanHtml = try {
                                    com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
                                } catch (_: Exception) {
                                    htmlResult
                                }
                                if (cleanHtml.isNotBlank()) {
                                    mangaDirVM.initDirectoryFromWeb(threadUrl, cleanHtml, pageTitle)
                                }
                            }
                        } else {
                            Log.i(
                                "OtherWebPage",
                                "非图区帖子(${sectionName})，跳过本地目录生成与缓存"
                            )
                        }
                    }
                }
            }
        }
    }
    var isHistoryCleared by remember { mutableStateOf(false) }
    LaunchedEffect(otherWebView) {
        otherWebView.webViewClient = object : YamiboWebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, pageUrl, favicon)
                if (url == "about:blank" || url.contains("warmup=true")) return
                isLoading = true
                currentUrl = pageUrl

                if (view != null) {
                    val list = view.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                }

                view?.evaluateJavascript(hideCommand, null)
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
                view?.evaluateJavascript(
                    """
                    (function(){
                        var a = document.querySelector('.header h2 a');
                        if (!a) return false;
                        var t = a.innerText;
                        return t.indexOf('中文百合漫画区') !== -1 || 
                               t.indexOf('貼圖區') !== -1 || 
                               t.indexOf('原创图作区') !== -1 || 
                               t.indexOf('百合漫画图源区') !== -1;
                    })()
                    """.trimIndent()
                ) { result ->
                    isMangaSection = result == "true"
                }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                view?.evaluateJavascript(
                    """
                    (function(){
                        window.__pswpInit = function() {
                            if (window.__globalPswpAttached) return;
                            var pswp = document.querySelector('.pswp');
                            if (!pswp) {
                                var bodyObserver = new MutationObserver(function(mutations, obs) {
                                    if (document.querySelector('.pswp')) {
                                        obs.disconnect();
                                        window.__pswpInit();
                                    }
                                });
                                bodyObserver.observe(document.body, { childList: true, subtree: true });
                                return;
                            }
                            window.__globalPswpAttached = true;
                            
                            var checkState = function() {
                                var isOpen = pswp.classList.contains('pswp--open') || 
                                             pswp.classList.contains('pswp--visible') || 
                                             (getComputedStyle(pswp).display !== 'none' && getComputedStyle(pswp).opacity > 0);
                                if (window.__pswpLastState !== isOpen) {
                                    window.__pswpLastState = isOpen;
                                    if (window.AndroidFullscreen) window.AndroidFullscreen.notify(isOpen);
                                    if (isOpen) {
                                        setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 100);
                                    }
                                }
                            };
                            
                            var pswpObserver = new MutationObserver(checkState);
                            pswpObserver.observe(pswp, { attributes: true, attributeFilter: ['class', 'style'] });
                            checkState();
                        };
                        window.__pswpInit();
                    })()
                    """.trimIndent(), null
                )
                if (!isHistoryCleared) {
                    view?.clearHistory()
                    isHistoryCleared = true
                }
                isLoading = false

                canGoBack = view?.let {
                    val list = it.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    baseIndex != -1 && list.currentIndex > baseIndex
                } ?: false

                val currentTid = MangaTitleCleaner.extractTidFromUrl(finishedUrl ?: "")
                val originalTid = MangaTitleCleaner.extractTidFromUrl(url)

                if (currentTid != null && originalTid != null && currentTid != originalTid) {
                    return
                }
                val extractPage = { urlStr: String? ->
                    var page = 1
                    if (urlStr != null) {
                        val pageMatch = Regex("page=(\\d+)").find(urlStr)
                        if (pageMatch != null) {
                            page = pageMatch.groupValues[1].toIntOrNull() ?: 1
                        } else {
                            // 兼容静态化 URL，如 thread-12345-2-1.html
                            val threadMatch = Regex("thread-\\d+-(\\d+)").find(urlStr)
                            if (threadMatch != null) {
                                page = threadMatch.groupValues[1].toIntOrNull() ?: 1
                            }
                        }
                    }
                    page
                }
                // 3. 只有TID一致（或者是同一个帖子），才执行后续的页码提取和保存逻辑
                val currentPageNum = extractPage(finishedUrl)

                // 即使是“其他”页面，加载完也做一次解析
                val checkTypeJs = """
                    (function() {
                        var sectionHeader = document.querySelector('.header h2 a');
                        var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
                        var currentUrl = window.location.href;
                        var mangaSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
                        var isManga = mangaSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=30') !== -1;
                        var novelSections = ['文学区', 'TXT小说区', '轻小说/译文区'];
                        var isNovel = novelSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=55') !== -1;
                        if (isNovel) return 1;
                        if (isManga) return 2;
                        return 3;
                    })();
                """.trimIndent()

                view?.evaluateJavascript(checkTypeJs) { result ->
                    val typeCode = result?.toIntOrNull() ?: 3
                    scope.launch(Dispatchers.IO) {
                        FavoriteUtil.getFavoriteMap { map ->
                            map[url]?.let { fav ->
                                var changed = false
                                var newFav = fav

                                // 更新类型
                                if (fav.type != typeCode) {
                                    newFav = newFav.copy(type = typeCode)
                                    changed = true
                                }

                                // 更新阅读进度
                                if (fav.lastView != currentPageNum) {
                                    newFav = newFav.copy(lastView = currentPageNum)
                                    changed = true
                                }

                                // 只有发生改变时才写入DataStore
                                if (changed) {
                                    FavoriteUtil.updateFavorite(newFav)
                                }
                            }
                        }
                    }
                }
                view?.evaluateJavascript(
                    """
                    (function(){
                        var a = document.querySelector('.header h2 a');
                        if (!a) return false;
                        var t = a.innerText;
                        return t.indexOf('中文百合漫画区') !== -1 || 
                               t.indexOf('貼圖區') !== -1 || 
                               t.indexOf('原创图作区') !== -1 || 
                               t.indexOf('百合漫画图源区') !== -1;
                    })()
                    """.trimIndent()
                ) { result ->
                    isMangaSection = result == "true"
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

        if (otherWebView.url == null || otherWebView.tag?.toString()
                ?.startsWith("recycled") == true || otherWebView.url == "about:blank"
        ) {
            otherWebView.tag = null
            FavoriteUtil.getFavoriteMap { map ->
                val lastSavedPage = map[url]?.lastView ?: 1
                val startUrl = getPagedUrl(finalUrl, lastSavedPage)

                scope.launch(Dispatchers.Main) {
                    otherWebView.loadUrl(startUrl)
                }
            }
        }
    }

    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            pendingNavigateUrl?.let { navigateUrl ->
                isLoading = true
                otherWebView.loadUrl(navigateUrl)
                pendingNavigateUrl = null
            }
        } else if (isFullscreenState.value && autoOpenMangaMode) {
            autoOpenMangaMode = false
        }
    }

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

            otherWebView.evaluateJavascript(clickJs, null)
            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                otherWebView.evaluateJavascript(
                    "var style = document.getElementById('manga-transition-style'); if (style) style.remove();",
                    null
                )
            }
        }
    }

    BackHandler(enabled = true) {
        if (canGoBack) {
            otherWebView.goBack()
        } else {
            performExit()
        }
    }

    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (navBarsPadding.value > lockedNavHeightValue) lockedNavHeightValue = navBarsPadding.value
    val lockedNavHeight = lockedNavHeightValue.dp

    val statusBarsPaddingVal = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var lockedStatusHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (statusBarsPaddingVal.value > lockedStatusHeightValue) lockedStatusHeightValue = statusBarsPaddingVal.value
    val lockedStatusHeight = lockedStatusHeightValue.dp

    val isFullscreen = isFullscreenState.value || autoOpenMangaMode
    val topSpacerColor = if (isFullscreen) Color.Black else YamiboColors.primary

    Box(modifier = Modifier
        .fillMaxSize()
        .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(lockedStatusHeight)
                .background(topSpacerColor)
                .align(Alignment.TopCenter)
                .zIndex(1f)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = lockedStatusHeight,
                    bottom = lockedNavHeight
                )
        ) {
            AndroidView(
                factory = {
                    (otherWebView.parent as? ViewGroup)?.removeView(otherWebView)
                    otherWebView
                },
                update = {
                    val list = it.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                    currentUrl = it.url
                },
                onRelease = {
                    timeoutJob?.cancel()
                    it.apply {
                        removeJavascriptInterface("AndroidFullscreen")
                    }
                    WebViewPool.release(it)
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
                            otherWebView,
                            otherWebView.url ?: url
                        )
                    }) { Text("重试") }
                }
            }
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f))
                        .pointerInput(Unit) {
                            detectTapGestures { }
                            detectVerticalDragGestures { _, _ -> }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = YamiboColors.secondary)
                }
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
}