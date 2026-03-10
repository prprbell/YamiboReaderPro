package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import java.net.URLEncoder

// 用于在WebView外部保存登录状态的单例对象
object BBSPageState {
    var lastLoginState: Boolean? = null
    var hasSuccessfullyLoaded: Boolean = false
}

// 用于接收大图打开/关闭的通知
class FullscreenApi(
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

class NativeMangaJSInterface(
    private val navController: NavController,
    private val getCurrentUrl: () -> String?,
    private val onActionDone: () -> Unit
) {
    @JavascriptInterface
    fun openNativeManga(urlsJoined: String, clickedIndex: Int, html: String, title: String) {
        val urls = urlsJoined.split("|||").filter { it.isNotBlank() }
        Handler(Looper.getMainLooper()).post {
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempMangaUrls = urls
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempMangaIndex = clickedIndex
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempHtml = html
            org.shirakawatyu.yamibo.novel.global.GlobalData.tempTitle = title

            // 通知 Compose 层关闭黑屏遮罩
            onActionDone()

            // 跳转到原生漫画页面
            val passUrl = getCurrentUrl() ?: "https://bbs.yamibo.com/forum.php"
            val encodedUrl = java.net.URLEncoder.encode(passUrl, "utf-8")
            navController.navigate("NativeMangaPage?url=$encodedUrl")
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun BBSPage(
    webView: WebView,
    isSelected: Boolean,
    cookieFlow: Flow<String>,
    navController: NavController
) {
    SetStatusBarColor(YamiboColors.primary)
    val indexUrl = "https://bbs.yamibo.com/forum.php"
    val bbsUrl = "https://bbs.yamibo.com/index.php?mobile=2"
    val baseBbsUrl = "https://bbs.yamibo.com/"

    val activity = LocalContext.current as? Activity

    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }

    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    var autoOpenMangaMode by remember { mutableStateOf(false) }
    var isMangaSection by remember { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl, pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl, pageTitle)
    }
    // ----- 全屏状态控制 -----
    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    // 强制获取ViewModel
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )

    // 监听全屏状态并切换UI
    // 1. 专门控制系统 UI 显隐
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

    // 2. 统一处理全屏状态变化时的核心业务逻辑
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            webView.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                    window.pswpObserverAttached = false; // 重置标记，确保下次进入能重新绑定
                })();
                """.trimIndent(),
                null
            )

            // 处理积压的“下一话”跳转任务
            pendingNavigateUrl?.let { url ->
                isLoading = true
                webView.loadUrl(url)
                pendingNavigateUrl = null
            }
        } else {
            // 大图成功打开，关闭 Compose 层的黑屏遮罩
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
            }
            // 大图模式下，抓取并解析当前页面的目录
            currentUrl?.let { url ->
                if (url.contains("mod=viewthread") && url.contains("tid=")) {
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

                    webView.evaluateJavascript(checkSectionJs) { result ->
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
                            val pageTitle = webView.title ?: ""
                            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
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
                            Log.i("BBSPage", "非图区帖子(${sectionName})，跳过本地目录生成与缓存")
                        }
                    }
                }
            }
        }
    }
    // 1. 监听大图退出状态，执行积压的跳转任务
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            pendingNavigateUrl?.let { url ->
                isLoading = true

                webView.loadUrl(url)
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
                        // 如果当前版块不在白名单中，立刻取消自动进大图模式
                        if (!isAllowedSection) {
                            if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                                window.AndroidFullscreen.notifyMangaActionDone();
                            }
                            return;
                        }
                    }
                    
                    var typeLabel = document.querySelector('.view_tit em');
                    if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                        // 如果是公告帖，立刻通知 Android 取消漫画黑屏模式，不执行后续点击逻辑
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                            window.AndroidFullscreen.notifyMangaActionDone();
                        }
                        return; 
                    }
                    // 1. 穿上隐身衣，防止穿帮
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

                    var isDone = false;
                    var attempts = 0;
                    var maxAttempts = 25; // 探测上限：5秒 (25 * 200ms)

                    // 2. 改用高频探测：每 200 毫秒检查一次状态
                    var timer = setInterval(function() {
                        if (isDone) {
                            clearInterval(timer);
                            return;
                        }
                        attempts++;

                        // 步骤 A: 检查 PhotoSwipe 的全屏容器 (.pswp) 是否已经生成
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

                        // 步骤 B: 还没弹出来？继续尝试点击图片
                        // 因为我们不知道网页自带的 JS 到底在什么时候绑定完成，所以要持续“叩门”
                        var links = document.querySelectorAll('a[data-pswp-width], .img_one a.orange, .message a.orange, .postmessage a.orange');
                        var targetEl = null;
                        for (var i = 0; i < links.length; i++) {
                            var href = links[i].getAttribute('href') || '';
                            var innerHtml = links[i].innerHTML || '';
                            // 核心过滤：跳过所有 .gif 后缀的链接，以及属于论坛静态资源(表情/分割线)的链接
                            if (href.toLowerCase().indexOf('.gif') === -1 && href.indexOf('static/image/') === -1 && innerHtml.indexOf('static/image/') === -1) {
                                targetEl = links[i];
                                break; // 找到第一张真正的正文图片，跳出循环
                            }
                        }
                        
                        if (targetEl) {
                            if (window.NativeMangaApi) {
                                var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                                var urls = [];
                                for (var i = 0; i < allImgs.length; i++) {
                                    var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                                    if (rawSrc) {
                                        urls.push(new URL(rawSrc, document.baseURI).href);
                                    }
                                }
                                window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.documentElement.outerHTML, document.title);
                                clearInterval(timer);
                                return;
                            } else {
                                targetEl.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                            }
                        }

                        // 步骤 C: 终极防死锁兜底
                        // 不管有图没图，只要 5 秒了还没看到 .pswp 出现，无条件放弃并恢复网页
                        if (attempts >= maxAttempts) {
                            isDone = true;
                            clearInterval(timer);
                            abortAndNotify();
                        }
                    }, 200);
                })();
            """.trimIndent()

            webView.evaluateJavascript(clickJs, null)

            // Compose 层的安全网：防止极端情况下 JS 引擎崩溃或报错导致黑屏无法解除
            // 给它比 JS 多 1 秒的宽限期
            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                webView.evaluateJavascript(
                    """
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                """.trimIndent(), null
                )
            }
        }
    }
    // 防止退出BBS页面时导航栏消失
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            bottomNavBarVM.setBottomNavBarVisibility(true)
        }
    }
    // ----- 全屏状态控制结束 -----
    fun isLoggedIn(cookie: String): Boolean {
        return cookie.contains("EeqY_2132_auth=")
    }

    lateinit var startLoading: (url: String) -> Unit

    fun runTimeout(onTimeout: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(15000)
            if (isLoading) {
                onTimeout()
            }
        }
    }

    startLoading = { url: String ->
        isLoading = true
        showLoadError = false
        retryCount = 0

        runTimeout {
            Log.w("BBSPage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout {
                Log.e("BBSPage", "Retry timed out. Giving up.")
                isLoading = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.reload()
        }

        webView.loadUrl(url)
    }

    // 当页面被选中时，检查登录状态是否变化
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
            return@LaunchedEffect
        }

        webView.onResume()

        val currentCookie = cookieFlow.first()
        val currentLoginState = isLoggedIn(currentCookie)

        Log.d("BBSPage", "Page selected. Current cookie: ${currentCookie.take(50)}...")
        Log.d(
            "BBSPage",
            "Current login state: $currentLoginState, Last login state: ${BBSPageState.lastLoginState}, Has loaded: ${BBSPageState.hasSuccessfullyLoaded}"
        )

        val needsLoad = when {
            !BBSPageState.hasSuccessfullyLoaded -> {
                Log.i("BBSPage", "First time or previous load failed, loading...")
                true
            }

            webView.url.isNullOrEmpty() || webView.url == "about:blank" -> {
                Log.i("BBSPage", "WebView URL is empty or blank, reloading...")
                true
            }

            BBSPageState.lastLoginState != null && BBSPageState.lastLoginState != currentLoginState -> {
                Log.i(
                    "BBSPage",
                    "Login state changed from ${BBSPageState.lastLoginState} to $currentLoginState. Reloading page..."
                )
                true
            }

            else -> {
                Log.d("BBSPage", "WebView state is good, no reload needed")
                false
            }
        }

        if (needsLoad) {
            startLoading(indexUrl)
        }
    }

    LaunchedEffect(webView, isSelected) {
        if (!isSelected) {
            return@LaunchedEffect
        }

        webView.webViewClient = object : YamiboWebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                canGoBack = view?.canGoBack() ?: false
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)

                pageTitle = view?.title ?: ""

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

            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                showLoadError = false
                super.onPageFinished(view, url)
                canGoBack = view?.canGoBack() ?: false
                // 检查是否加载了首页
                if (view != null && url != null) {
                    val isHomepage = url == indexUrl || url == bbsUrl || url == baseBbsUrl
                    if (isHomepage) {
                        // 清除历史记录
                        view.clearHistory()
                    }
                }
                // 更新canGoBack状态
                canGoBack = view?.canGoBack() ?: false

                // 只有在成功加载后才更新状态
                if (url != null && !url.contains("about:blank")) {
                    BBSPageState.hasSuccessfullyLoaded = true

                    // 在首次成功加载或登录状态变化后的成功加载时，更新lastLoginState
                    scope.launch {
                        val currentCookie = cookieFlow.first()
                        val currentLoginState = isLoggedIn(currentCookie)
                        if (BBSPageState.lastLoginState == null || BBSPageState.lastLoginState != currentLoginState) {
                            Log.d(
                                "BBSPage",
                                "Updating lastLoginState to $currentLoginState after successful load"
                            )
                            BBSPageState.lastLoginState = currentLoginState
                        }
                    }
                }
                // 【新增重构逻辑】：探测漫画版块，并注入拦截器
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
                    // 如果是图区，拦截图片点击，发送给原生 NativeMangaApi
                    if (isMangaSection) {
                        val injectJs = """
                            javascript:(function() {
                                document.addEventListener('click', function(e) {
                                    var targetImg = e.target.closest('.img_one img, .message img');
                                    // 过滤表情包
                                    if (targetImg && targetImg.src.indexOf('smiley') === -1) { 
                                        e.preventDefault(); 
                                        e.stopPropagation();
                                        
                                        var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                                        var urls = [];
                                        var clickedIndex = 0;
                                        for (var i = 0; i < allImgs.length; i++) {
                                            var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                                            if (rawSrc) {
                                                // 强制转换为绝对路径
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
                        // 加载失败时，标记为未成功加载
                        BBSPageState.hasSuccessfullyLoaded = false
                    }
                }
            }

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
                        // 加载失败时，标记为未成功加载
                        BBSPageState.hasSuccessfullyLoaded = false
                    }
                }
            }
        }

        canGoBack = webView.canGoBack()
    }

    // 监听页面离开，保存当前URL
    DisposableEffect(isSelected) {
        onDispose {
            if (!isSelected) {
                Log.d("BBSPage", "Page deselected, current URL: ${webView.url}")
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            canGoBack -> webView.goBack()
            navController.currentBackStack.value.size > 1 -> {
                navController.popBackStack()
            }

            else -> {
                activity?.finish()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                webView.settings.apply {
                    // 强制遵守网页的viewport
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // 禁用系统缩放
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    // 锁定字体比例
                    textZoom = 100
                    // 开启本地存储
                    domStorageEnabled = true
                    // 启用JavaScript
                    javaScriptEnabled = true
                }
                webView.addJavascriptInterface(
                    FullscreenApi(
                        onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen },
                        onMangaActionDone = { autoOpenMangaMode = false }
                    ),
                    "AndroidFullscreen"
                )
                val nativeApi = NativeMangaJSInterface(
                    navController,
                    { currentUrl },
                    { autoOpenMangaMode = false })
                webView.addJavascriptInterface(nativeApi, "NativeMangaApi")
                webView
            },
            update = { view ->
                canGoBack = view.canGoBack()
                currentUrl = view.url
            },
            onRelease = {
                it.stopLoading()
                it.onPause()
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
                    startLoading(webView.url ?: indexUrl)
                }) {
                    Text("重试")
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YamiboColors.secondary
            )
        }

        ReaderModeFAB(
            visible = canConvertToReader && !isLoading && !showLoadError && !isFullscreenState.value,
            onClick = {
                currentUrl?.let { url ->
                    ReaderModeDetector.extractThreadPath(url)?.let { threadPath ->
                        val encodedPath = URLEncoder.encode(threadPath, "utf-8")
                        navController.navigate("ReaderPage/$encodedPath")
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp)
        )
        // 无缝切换漫画章节的黑屏遮罩层
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
