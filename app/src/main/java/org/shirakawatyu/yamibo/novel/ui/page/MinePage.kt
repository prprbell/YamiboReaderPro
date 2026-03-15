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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import java.io.ByteArrayInputStream
import java.net.URLEncoder

private val hideCommand = """
    javascript:(function() {
        if (window.location.href.indexOf('do=pm') !== -1) {
            return;
        }
        if (window.location.href.indexOf('do=blog') !== -1) {
            return;
        }
        var style = document.createElement('style');
        style.innerHTML = '.my, .mz { visibility: hidden !important; pointer-events: none !important; }'; 
        document.head.appendChild(style);
    })()
""".trimIndent()

class FullscreenApiMine {
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

class NativeMangaMineJSInterface {
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

/**
 * 个人中心，WebView每次访问时创建，离开时销毁
 *
 * @param isSelected 表示当前页面是否被选中，用于控制页面加载逻辑和状态更新。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MinePage(
    isSelected: Boolean,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    SetStatusBarColor(YamiboColors.primary)
    val mineUrl = "https://bbs.yamibo.com/home.php?mod=space&do=profile&mycenter=1&mobile=2"
    val bbsUrl = "https://bbs.yamibo.com/?mobile=2"
    val baseBbsUrl = "https://bbs.yamibo.com/"      // 根URL
    val indexUrl = "https://bbs.yamibo.com/forum.php" // 论坛主页

    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    var autoOpenMangaMode by remember { mutableStateOf(false) }
    var isMangaSection by remember { mutableStateOf(false) }
    var savedMangaUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var needFallbackToHome by rememberSaveable { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl, pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl, pageTitle)
    }
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )
    lateinit var startLoading: (webView: WebView, url: String) -> Unit

    fun runTimeout(webView: WebView, onTimeout: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(10000)
            if (isLoading) {
                onTimeout()
            }
        }
    }


    startLoading = { webView: WebView, url: String ->
        isLoading = true
        showLoadError = false
        retryCount = 0

        runTimeout(webView) {
            Log.w("MinePage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                Log.e("MinePage", "Retry timed out. Giving up.")
                isLoading = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.reload()
        }

        webView.loadUrl(url)
    }
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    // ----- 全屏状态控制 -----
    val isFullscreenState = remember { mutableStateOf(false) }
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context as ComponentActivity)
    DisposableEffect(Unit) {
        onDispose {
            val currentRoute = navController.currentDestination?.route ?: ""
            if (!currentRoute.startsWith("NativeMangaPage") && !currentRoute.startsWith("ReaderPage")) {
                activity?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
                bottomNavBarVM.setBottomNavBarVisibility(true)
            }
        }
    }
    // ----- 全屏状态控制结束 -----
    val fullscreenApi = remember { FullscreenApiMine() }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }

    val nativeMangaApi = remember { NativeMangaMineJSInterface() }

    val mineWebView = remember {
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
            addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            this.webChromeClient = webChromeClient
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (autoOpenMangaMode) {
                    autoOpenMangaMode = false
                }
                mineWebView.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        mineWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
            val cleanHtml = try {
                com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
            } catch (e: Exception) {
                htmlResult?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
            }

            val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
            GlobalData.tempMangaUrls = urls
            GlobalData.tempMangaIndex = clickedIndex
            GlobalData.tempHtml = cleanHtml
            GlobalData.tempTitle = title

            mineWebView.evaluateJavascript("window.stop();", null)
            mineWebView.stopLoading()
            mineWebView.onPause()

            autoOpenMangaMode = false

            val passUrl = currentUrl ?: "https://bbs.yamibo.com/forum.php"
            savedMangaUrl = passUrl // 保存以便返回时重载

            val encodedUrl = java.net.URLEncoder.encode(passUrl, "utf-8")
            val encodedOriginal = java.net.URLEncoder.encode(passUrl, "utf-8")
            navController.navigate("NativeMangaPage?url=$encodedUrl&originalUrl=$encodedOriginal")
        }
    }
    ActivityWebViewLifecycleObserver(mineWebView)
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
        }
    }

    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            mineWebView.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                    window.pswpObserverAttached = false; // 重置标记，确保下次进入能重新绑定
                })();
                """.trimIndent(),
                null
            )
            pendingNavigateUrl?.let { url ->
                isLoading = true
                mineWebView.loadUrl(url)
                pendingNavigateUrl = null
            }
        } else {
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
            }
            currentUrl?.let { url ->
                if (url.contains("mod=viewthread") && url.contains("tid=")) {
                    val checkSectionJs = """
                        (function() {
                            var sectionHeader = document.querySelector('.header h2 a');
                            if (sectionHeader) return sectionHeader.innerText.trim();
                            var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
                            if (nav) return nav.innerText.trim();
                            return '';
                        })();
                    """.trimIndent()

                    mineWebView.evaluateJavascript(checkSectionJs) { result ->
                        val sectionName = try {
                            com.alibaba.fastjson2.JSON.parse(result) as? String ?: ""
                        } catch (e: Exception) {
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
                            val pageTitle = mineWebView.title ?: ""
                            mineWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                val cleanHtml = try {
                                    com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
                                } catch (e: Exception) {
                                    htmlResult.trim('"').replace("\\u003C", "<")
                                        .replace("\\\"", "\"")
                                }
                                if (cleanHtml.isNotBlank()) {
                                    mangaDirVM.initDirectoryFromWeb(url, cleanHtml, pageTitle)
                                }
                            }
                        } else {
                            Log.i("MinePage", "非图区帖子(${sectionName})，跳过本地目录生成与缓存")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(mineWebView, isSelected) {
        mineWebView.webViewClient = object : YamiboWebViewClient() {
            var contentImageCount = 0

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                contentImageCount = 0
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                canGoBack = view?.canGoBack() ?: false
                view?.loadUrl(hideCommand)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""
                val accept = request?.requestHeaders?.get("Accept") ?: ""

                val isImage = accept.contains("image/", ignoreCase = true) ||
                        urlStr.contains(
                            Regex(
                                "\\.(jpg|jpeg|png|webp|gif)",
                                RegexOption.IGNORE_CASE
                            )
                        ) ||
                        urlStr.contains("attachment")

                if (request?.isForMainFrame == false && isImage) {
                    if (!urlStr.contains("smiley") && !urlStr.contains("avatar") &&
                        !urlStr.contains("common") && !urlStr.contains("static/image")
                    ) {

                        val count = synchronized(this) { contentImageCount++ }

                        val isHomePage =
                            currentUrl == mineUrl || currentUrl?.contains("do=profile") == true
                        if (GlobalData.isDataSaverMode.value && !isHomePage) {
                            if (count >= 3) {
                                return WebResourceResponse(
                                    "image/png",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                        } else {
                            val delayMs = when {
                                count < 2 -> 0L
                                count < 7 -> (count - 1) * 400L
                                else -> 2000L
                            }

                            if (delayMs > 0L) {
                                try {
                                    Thread.sleep(delayMs)
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && url.startsWith("https://bbs.yamibo.com/home.php?mod=space&do=profile")) {
                    view?.clearHistory()
                    canGoBack = false
                } else {
                    canGoBack = view?.canGoBack() ?: false
                }
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)

                pageTitle = view?.title ?: ""

                // 页面内容已可见，立即停止加载圈
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

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                showLoadError = false
                super.onPageFinished(view, url)
                currentUrl = url
                if (url != null && url.startsWith("https://bbs.yamibo.com/home.php?mod=space&do=profile")) {
                    view?.clearHistory()
                }

                if (isSelected && view != null) {
                    val currentUrl = view.url ?: ""

                    val isHomepage = currentUrl == bbsUrl ||
                            currentUrl == baseBbsUrl ||
                            currentUrl == indexUrl
                    if (isHomepage) {
                        startLoading(view, mineUrl)
                    }
                }
                canGoBack = view?.canGoBack() ?: false
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
                    // 如果是漫画/图区，拦截图片点击事件发送给 NativeMangaApi
                    if (isMangaSection) {
                        val injectJs = """
                            javascript:(function() {
                                document.addEventListener('click', function(e) {
                                    // 1. 扩大捕获范围：包括 a 标签和 li 标签等容器
                                    var targetContainer = e.target.closest('.img_one li, .img_one a, .message a, .img_one img, .message img');
                                    if (!targetContainer) return;
                                    
                                    // 2. 尝试从中找出真正的 img 元素
                                    var targetImg = targetContainer.tagName.toLowerCase() === 'img' ? targetContainer : targetContainer.querySelector('img');
                                    
                                    // 3. 如果找到了图片，且不是论坛表情，则拦截
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
                                                // 兼容 zsrc, file 和 src
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

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                timeoutJob?.cancel()
                retryCount = 0
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isLoading = false
                    if (retryCount == 0) {
                        showLoadError = true
                    }
                }
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                timeoutJob?.cancel()
                retryCount = 0
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    isLoading = false
                    if (retryCount == 0) {
                        showLoadError = true
                    }
                }
            }
        }

        if (isSelected && mineWebView.url == null) {
            // 从漫画返回
            if (savedMangaUrl != null) {
                startLoading(mineWebView, savedMangaUrl!!)
                savedMangaUrl = null
                needFallbackToHome = true
            } else {
                // 如果不是从漫画返回的，回到首页
                startLoading(mineWebView, mineUrl)
            }
        } else {
            canGoBack = mineWebView.canGoBack()
        }
    }
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
        }
    }

    // 1. 监听大图退出状态，执行积压的跳转任务
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            pendingNavigateUrl?.let { url ->
                isLoading = true

                mineWebView.loadUrl(url)
                pendingNavigateUrl = null
            }
        } else if (isFullscreenState.value && autoOpenMangaMode) {
            autoOpenMangaMode = false
        }
    }

    // 2. 监听页面加载完成，注入基于事件驱动的监听 JS
    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            val clickJs = """
                (function() {
                    // 1. 检查版块白名单
                    var sectionHeader = document.querySelector('.header h2 a');
                    var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
                    if (sectionName !== '') {
                        var allowedSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
                        var isAllowedSection = false;
                        for (var k = 0; k < allowedSections.length; k++) {
                            if (sectionName.indexOf(allowedSections[k]) !== -1) {
                                isAllowedSection = true;
                                break;
                            }
                        }
                        if (!isAllowedSection) {
                            if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                                window.AndroidFullscreen.notifyMangaActionDone();
                            }
                            return;
                        }
                    }
                    
                    // 2. 检查公告帖拦截
                    var typeLabel = document.querySelector('.view_tit em');
                    if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                            window.AndroidFullscreen.notifyMangaActionDone();
                        }
                        return; 
                    }

                    // 3. 注入过渡黑屏样式 (防止闪白)
                    if (!document.getElementById('manga-transition-style')) {
                        var style = document.createElement('style');
                        style.id = 'manga-transition-style';
                        style.innerHTML = 'body > *:not(.pswp) { opacity: 0 !important; pointer-events: none !important; } body { background: #000 !important; }';
                        document.head.appendChild(style);
                    }

                    function abortAndNotify() {
                        var style = document.getElementById('manga-transition-style');
                        if (style) style.remove();
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                            window.AndroidFullscreen.notifyMangaActionDone();
                        }
                    }

                    // ==========================================
                    // 轨道 A: NativeMangaApi (原生路径)
                    // ==========================================
                    if (window.NativeMangaApi) {
                        var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                        if (allImgs.length > 0) {
                            var urls = [];
                            for (var i = 0; i < allImgs.length; i++) {
                                var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                                if (rawSrc) {
                                    urls.push(new URL(rawSrc, document.baseURI).href);
                                }
                            }
                            if (urls.length > 0) {
                                window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.title);
                                return;
                            }
                        }
                    }

                    // ==========================================
                    // 轨道B: PhotoSwipe降级方案
                    // ==========================================
                    var clickTimer = null;
                    var timeoutTimer = null;
                    
                    var observer = new MutationObserver(function(mutations, obs) {
                        if (document.querySelector('.pswp')) {
                            obs.disconnect(); // 立即停止监听
                            clearTimeout(timeoutTimer); // 取消 5 秒兜底
                            if (clickTimer) clearInterval(clickTimer); // 成功后立刻停止点击重试
                            
                            if (window.AndroidFullscreen) {
                                window.AndroidFullscreen.notify(true);
                                window.AndroidFullscreen.notifyMangaActionDone();
                            }
                            return;
                        }
                    });

                    // 开始监听 body 的子节点变化
                    observer.observe(document.body, { childList: true, subtree: true });

                    var clickAttempts = 0;
                    var maxClicks = 10;

                    function tryClickTarget() {
                        if (clickAttempts >= maxClicks) {
                            if (clickTimer) clearInterval(clickTimer);
                            return;
                        }
                        // 双重保险：如果在定时器触发瞬间 .pswp 已经存在，放弃本次点击防止动画错乱
                        if (document.querySelector('.pswp')) return; 
                        
                        clickAttempts++;
                        var links = document.querySelectorAll('a[data-pswp-width], .img_one a.orange, .message a.orange, .postmessage a.orange');
                        var clicked = false;
                        for (var i = 0; i < links.length; i++) {
                            var href = links[i].getAttribute('href') || '';
                            var innerHtml = links[i].innerHTML || '';
                            if (href.toLowerCase().indexOf('.gif') === -1 && href.indexOf('static/image/') === -1 && innerHtml.indexOf('static/image/') === -1) {
                                links[i].dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                                clicked = true;
                                break; 
                            }
                        }

                        if (!clicked) {
                            var fallbackImgs = document.querySelectorAll('.img_one img');
                            if(fallbackImgs.length > 0 && fallbackImgs[0].parentElement && fallbackImgs[0].parentElement.tagName === 'A'){
                                fallbackImgs[0].parentElement.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                            }
                        }
                    }

                    // 1. 立即执行第 1 次点击（如果网页加载够快，这次直接就成了）
                    tryClickTarget();

                    // 2. 如果第 1 次被网页忽略，开启后备隐藏重试（每 250ms 一次）
                    clickTimer = setInterval(tryClickTarget, 250);

                    // 3. 5 秒超时终极防死锁清理
                    timeoutTimer = setTimeout(function() {
                        observer.disconnect();
                        if (clickTimer) clearInterval(clickTimer);
                        abortAndNotify();
                    }, 5000);
                })();
            """.trimIndent()

            mineWebView.evaluateJavascript(clickJs, null)

            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                mineWebView.evaluateJavascript(
                    """
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                """.trimIndent(), null
                )
            }
        }
    }
    BackHandler(enabled = canGoBack || needFallbackToHome) {
        if (canGoBack) {
            mineWebView.goBack()
        } else if (needFallbackToHome) {
            needFallbackToHome = false
            startLoading(mineWebView, mineUrl)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mineWebView
            },
            update = {
                canGoBack = it.canGoBack()
                currentUrl = it.url
                pageTitle = it.title ?: ""
            },
            onRelease = {
                timeoutJob?.cancel()
                it.apply {
                    onPause()
                    stopLoading()
                    webViewClient = android.webkit.WebViewClient()
                    setWebChromeClient(null)
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
            }
        )
        ReaderModeFAB(
            visible = canConvertToReader && !isLoading && !showLoadError && !isFullscreenState.value,
            onClick = {
                currentUrl?.let { url ->
                    ReaderModeDetector.extractThreadPath(url)?.let { threadPath ->
                        savedMangaUrl = url
                        val encodedPath = URLEncoder.encode(threadPath, "utf-8")
                        navController.navigate("ReaderPage/$encodedPath")
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 150.dp)
        )

        if (showLoadError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "加载失败",
                    modifier = Modifier.size(48.dp),
                    tint = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "页面加载失败",
                    fontSize = 18.sp,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "请尝试切换至其他界面再切换回来，或重启应用。",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    startLoading(mineWebView, mineWebView.url ?: mineUrl)
                }) {
                    Text("重试")
                }
            }
        }

        // 局部加载指示器
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YamiboColors.secondary
            )
        }

        if (autoOpenMangaMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                        detectVerticalDragGestures { _, _ -> }
                    }
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }
    }
}

