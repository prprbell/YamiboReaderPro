package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import java.io.ByteArrayInputStream
import java.net.URLEncoder

// 用于在WebView外部保存登录状态的单例对象
object BBSPageState {
    var lastLoginState: Boolean? = null
    var hasSuccessfullyLoaded: Boolean = false
    var fullscreenApi: FullscreenApi? = null
    var nativeMangaApi: NativeMangaJSInterface? = null
    var isErrorState: Boolean = false
}

// 用于接收大图打开/关闭的通知

class FullscreenApi {
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

class NativeMangaJSInterface {
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

@SuppressLint("RestrictedApi", "JavascriptInterface")
@Composable
fun BBSPage(
    webView: WebView,
    isSelected: Boolean,
    cookieFlow: Flow<String>,
    navController: NavController
) {
    val indexUrl = "https://bbs.yamibo.com/forum.php"
    val mobileIndexUrl = "https://bbs.yamibo.com/forum.php?mobile=2"
    val bbsUrl = "https://bbs.yamibo.com/index.php?mobile=2"
    val baseBbsUrl = "https://bbs.yamibo.com/"

    val activity = LocalContext.current as? Activity

    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(BBSPageState.isErrorState) }
    LaunchedEffect(showLoadError) {
        BBSPageState.isErrorState = showLoadError
    }
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                webView.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    val fullscreenApi = remember {
        if (BBSPageState.fullscreenApi == null) {
            BBSPageState.fullscreenApi = FullscreenApi()
        }
        BBSPageState.fullscreenApi!!
    }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }

    val nativeMangaApi = remember {
        if (BBSPageState.nativeMangaApi == null) {
            BBSPageState.nativeMangaApi = NativeMangaJSInterface()
        }
        BBSPageState.nativeMangaApi!!
    }
    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
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

            webView.evaluateJavascript("window.stop();", null)
            webView.stopLoading()
            webView.onPause()

            autoOpenMangaMode = false
            val passUrl = currentUrl ?: "https://bbs.yamibo.com/forum.php"
            val encodedUrl = URLEncoder.encode(passUrl, "utf-8")
            navController.navigate("NativeMangaPage?url=$encodedUrl")
        }
    }
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )

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
                    // NativeMangaApi
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
                                return; // 提取成功，彻底终止后续逻辑
                            }
                        }
                    }

                    // ==========================================
                    // PhotoSwipe
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

                    tryClickTarget();

                    clickTimer = setInterval(tryClickTarget, 250);

                    timeoutTimer = setTimeout(function() {
                        observer.disconnect();
                        if (clickTimer) clearInterval(clickTimer);
                        abortAndNotify();
                    }, 5000);
                })();
            """.trimIndent()

            webView.evaluateJavascript(clickJs, null)

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
            webView.stopLoading()
            retryCount++

            runTimeout {
                isLoading = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.reload()
        }

        webView.loadUrl(url)
    }
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            webView.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('manga-transition-style');
                    if (style) style.remove();
                    window.pswpObserverAttached = false;
                })();
                """.trimIndent(),
                null
            )

            pendingNavigateUrl?.let { url ->
                startLoading(url)
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
    LaunchedEffect(Unit) {
        bottomNavBarVM.refreshEvent.collect { route ->
            if (route == "BBSPage") {
                val curl = webView.url
                // 如果当前页面有效则原地刷新，否则重载初始页
                if (!curl.isNullOrEmpty() && curl != "about:blank") {
                    isLoading = true
                    showLoadError = false
                    retryCount = 0

                    runTimeout {
                        webView.stopLoading()
                        retryCount++
                        runTimeout {
                            isLoading = false
                            showLoadError = true
                            webView.stopLoading()
                        }
                        webView.reload()
                    }
                    webView.reload()
                } else {
                    startLoading(mobileIndexUrl)
                }
            }
        }
    }

    LaunchedEffect(webView, isSelected) {
        if (!isSelected) {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
            return@LaunchedEffect
        }

        webView.webViewClient = object : YamiboWebViewClient() {
            var contentImageCount = 0
            var hasError = false
            val checkSectionAndInjectJs = """
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
                    var a = document.querySelector('.header h2 a');
                    var isManga = false;
                    if (a) {
                        var t = a.innerText;
                        isManga = t.indexOf('中文百合漫画区') !== -1 || 
                                  t.indexOf('貼圖區') !== -1 || 
                                  t.indexOf('原创图作区') !== -1 || 
                                  t.indexOf('百合漫画图源区') !== -1;
                    }
                    if (isManga) {
                        if (window._mangaClickInjected) return 'true';
                        window._mangaClickInjected = true;
                        
                        // 破坏可能触发原生PhotoSwipe的属性
                        var disablePhotoSwipe = function() {
                            var links = document.querySelectorAll('a[data-pswp-width], .img_one a, .message a');
                            for (var i = 0; i < links.length; i++) {
                                var aNode = links[i];
                                if (aNode.querySelector('img')) {
                                    aNode.removeAttribute('data-pswp-width');
                                    if (aNode.href && aNode.href.indexOf('javascript') === -1) {
                                        aNode.setAttribute('data-disabled-href', aNode.href);
                                        aNode.removeAttribute('href');
                                    }
                                }
                            }
                        };
                        disablePhotoSwipe();
                        // 监听动态加载的图片
                        var observer = new MutationObserver(disablePhotoSwipe);
                        observer.observe(document.body, { childList: true, subtree: true });
                        
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
                                    e.stopImmediatePropagation();
                                    
                                    var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                                    var urls = [];
                                    var clickedIndex = 0;
                                    for (var i = 0; i < allImgs.length; i++) {
                                        var rawSrc = allImgs[i].getAttribute('zsrc') ||
                                        allImgs[i].getAttribute('file') || allImgs[i].getAttribute('src');
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
                    }
                    return isManga ? 'true' : 'false';
                })()
            """.trimIndent()

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                GlobalData.webProgress.value = 0
                contentImageCount = 0
                hasError = false
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                canGoBack = view?.canGoBack() ?: false
            }

            override fun onFormResubmission(
                view: WebView?,
                dontResend: android.os.Message?,
                resend: android.os.Message?
            ) {
                resend?.sendToTarget()
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
                        val isHomePage = currentUrl == indexUrl ||
                                currentUrl == mobileIndexUrl ||
                                currentUrl == bbsUrl ||
                                currentUrl == baseBbsUrl ||
                                (currentUrl?.startsWith("https://bbs.yamibo.com/forum.php") == true && currentUrl?.contains(
                                    "mod="
                                ) == false)

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
                                count < 7 -> (count - 1) * 200L
                                else -> 1000L
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

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                pageTitle = view?.title ?: ""
                if (!hasError && isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    showLoadError = false
                }
                view?.evaluateJavascript(checkSectionAndInjectJs) { result ->
                    isMangaSection = result == "true" || result == "\"true\""
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                if (!hasError) {
                    showLoadError = false
                }
                super.onPageFinished(view, url)
                canGoBack = view?.canGoBack() ?: false
                if (view != null && url != null) {
                    val isHomepage =
                        url == indexUrl || url == bbsUrl || url == baseBbsUrl || url == mobileIndexUrl
                    if (isHomepage) {
                        view.clearHistory()
                    }
                }
                canGoBack = view?.canGoBack() ?: false

                if (url != null && !url.contains("about:blank")) {
                    BBSPageState.hasSuccessfullyLoaded = true
                }
                view?.evaluateJavascript(checkSectionAndInjectJs) { result ->
                    isMangaSection = result == "true" || result == "\"true\""
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                timeoutJob?.cancel()
                retryCount = 0
                super.onReceivedError(view, errorCode, description, failingUrl)
                hasError = true
                isLoading = false
                if (retryCount == 0) {
                    showLoadError = true
                    BBSPageState.hasSuccessfullyLoaded = false
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
                    hasError = true
                    isLoading = false
                    if (retryCount == 0) {
                        showLoadError = true
                        BBSPageState.hasSuccessfullyLoaded = false
                    }
                }
            }
        }

        canGoBack = webView.canGoBack()
        webView.onResume()

        while (true) {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""
            val currentLoginState = isLoggedIn(currentCookie)

            val isWebViewBlank = webView.url.isNullOrEmpty() || webView.url == "about:blank"

            if (isLoading) {
                BBSPageState.lastLoginState = currentLoginState
            } else if (isWebViewBlank) {
                BBSPageState.lastLoginState = currentLoginState
                startLoading(mobileIndexUrl)
            } else if (BBSPageState.lastLoginState != null && BBSPageState.lastLoginState != currentLoginState) {
                Log.i(
                    "BBSPage",
                    "状态变更: ${BBSPageState.lastLoginState} -> $currentLoginState, 准备刷新"
                )
                BBSPageState.lastLoginState = currentLoginState
                startLoading(mobileIndexUrl)
            } else if (BBSPageState.lastLoginState == null) {
                BBSPageState.lastLoginState = currentLoginState
            }

            delay(500)
        }
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
        // 判断当前是否已经在各类首页变体上
        val isHomepage = currentUrl == indexUrl || currentUrl == mobileIndexUrl ||
                currentUrl == bbsUrl || currentUrl == baseBbsUrl ||
                (currentUrl?.startsWith("https://bbs.yamibo.com/forum.php") == true && currentUrl?.contains(
                    "mod="
                ) == false)

        when {
            canGoBack -> {
                webView.goBack()
            }

            !isHomepage -> {
                startLoading(mobileIndexUrl)
            }

            navController.currentBackStack.value.size > 1 -> {
                navController.popBackStack()
            }

            else -> {
                activity?.finish()
            }
        }
    }
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (navBarsPadding.value > lockedNavHeightValue) lockedNavHeightValue = navBarsPadding.value
    val lockedNavHeight = lockedNavHeightValue.dp

    val statusBarsPaddingVal = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var lockedStatusHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
    if (statusBarsPaddingVal.value > lockedStatusHeightValue) lockedStatusHeightValue =
        statusBarsPaddingVal.value
    val lockedStatusHeight = lockedStatusHeightValue.dp

    val isFullscreen = isFullscreenState.value || autoOpenMangaMode
    val topSpacerColor = if (isFullscreen) Color.Black else YamiboColors.primary
    val bottomPad = if (isFullscreen) lockedNavHeight else (lockedNavHeight + 50.dp)

    Box(
        modifier = Modifier
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
                    bottom = bottomPad
                )
        ) {
            AndroidView(
                factory = {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webView.addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
                    webView.addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
                    webView
                },
                update = { view ->
                    canGoBack = view.canGoBack()
                    currentUrl = view.url
                    pageTitle = view.title ?: ""
                    view.requestLayout()
                    view.invalidate()
                },
                onRelease = {
                    it.onPause()
                }
            )

            if (showLoadError) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
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
                        "网页无法打开", // 模仿系统标题，但做得更美观
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "网络连接中断或页面加载失败，请检查网络后刷新",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val currentUrl = webView.url
                        if (!currentUrl.isNullOrEmpty() && currentUrl != "about:blank") {
                            isLoading = true
                            showLoadError = false
                            retryCount = 0

                            runTimeout {
                                webView.stopLoading()
                                retryCount++
                                runTimeout {
                                    isLoading = false
                                    showLoadError = true
                                    webView.stopLoading()
                                }
                                webView.reload()
                            }
                            webView.reload()
                        } else {
                            startLoading(mobileIndexUrl)
                        }
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("刷新页面")
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
                        val cleanUrl = url.substringBefore("#")

                        ReaderModeDetector.extractThreadPath(cleanUrl)?.let { threadPath ->
                            val encodedPath = URLEncoder.encode(threadPath, "utf-8")
                            navController.navigate("ReaderPage/$encodedPath")
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 150.dp)
            )
            // 切换漫画章节的黑屏遮罩层
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
}
