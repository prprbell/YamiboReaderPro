package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.alibaba.fastjson2.JSON
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
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

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
    var onGoBack: (() -> Unit)? = null
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
    @JavascriptInterface
    fun goBack() {
        Handler(Looper.getMainLooper()).post {
            onGoBack?.invoke()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MinePage(
    isSelected: Boolean,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    val mineUrl = "https://bbs.yamibo.com/home.php?mod=space&do=profile&mycenter=1&mobile=2"

    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var showLoadError by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var currentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var autoOpenMangaMode by remember { mutableStateOf(false) }
    var savedMangaUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var needFallbackToHome by rememberSaveable { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl, pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl, pageTitle)
    }
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )
    lateinit var startLoading: (webView: WebView, url: String) -> Unit

    fun evaluateCanGoBack(view: WebView?): Boolean {
        if (view == null || !view.canGoBack()) return false
        val currUrl = view.url ?: ""
        if (currUrl.contains("mod=space") && currUrl.contains("do=profile")) return false

        val list = view.copyBackForwardList()
        if (list.currentIndex <= 0) return false

        val backItem = list.getItemAtIndex(list.currentIndex - 1)
        val backUrl = backItem?.url ?: return false

        return backUrl.isNotBlank() &&
                backUrl != "about:blank" &&
                !backUrl.contains("warmup=true") &&
                !backUrl.startsWith("data:")
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

    startLoading = { webView: WebView, url: String ->
        isLoading = true
        hasError = false
        showLoadError = false
        retryCount = 0
        CookieManager.getInstance().setCookie(url, GlobalData.currentCookie)
        CookieManager.getInstance().flush()
        runTimeout(webView) {
            Log.w("MinePage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                Log.e("MinePage", "Retry timed out. Giving up.")
                hasError = true
                isLoading = false
                isPullRefreshing = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.loadUrl(url)
        }
        webView.loadUrl(url)
    }

    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    val bottomNavBarVM: BottomNavBarVM = viewModel(viewModelStoreOwner = context as ComponentActivity)

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

    val fullscreenApi = remember { FullscreenApiMine() }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }

    val nativeMangaApi = remember { NativeMangaMineJSInterface() }

    val mineWebView = remember {
        WebViewPool.acquire(context).apply {
            settings.apply {
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true

                loadsImagesAutomatically = true
                blockNetworkImage = false
            }
            addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            this.webChromeClient = webChromeClient
        }
    }

    nativeMangaApi.onGoBack = {
        if (evaluateCanGoBack(mineWebView)) {
            mineWebView.goBack()
        } else {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.refreshEvent.collect { route ->
            if (route == "MinePage") {
                isPullRefreshing = true
                isLoading = true
                showLoadError = false
                val curl = mineWebView.url
                if (!curl.isNullOrEmpty() && curl != "about:blank") {
                    mineWebView.reload()
                } else {
                    startLoading(mineWebView, mineUrl)
                }
            }
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
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val cleanHtml = try {
                    JSON.parse(htmlResult) as? String ?: ""
                } catch (_: Exception) {
                    htmlResult?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                }

                val urls = urlsJoined.split("|||").filter { it.isNotBlank() }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempMangaIndex = clickedIndex
                    GlobalData.tempHtml = cleanHtml
                    GlobalData.tempTitle = title

                    mineWebView.evaluateJavascript("window.stop();", null)
                    mineWebView.stopLoading()
                    mineWebView.onPause()

                    autoOpenMangaMode = false
                    val passUrl = currentUrl ?: "https://bbs.yamibo.com/forum.php"

                    savedMangaUrl = passUrl
                    val encodedUrl = java.net.URLEncoder.encode(passUrl, "utf-8")
                    val encodedOriginal = java.net.URLEncoder.encode(passUrl, "utf-8")
                    navController.navigate("NativeMangaPage?url=$encodedUrl&originalUrl=$encodedOriginal")
                }
            }
        }
    }

    ActivityWebViewLifecycleObserver(mineWebView)

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

    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            mineWebView.evaluateJavascript(
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
                            val pageTitle = mineWebView.title ?: ""
                            mineWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                val cleanHtml = try {
                                    com.alibaba.fastjson2.JSON.parse(htmlResult) as? String ?: ""
                                } catch (_: Exception) {
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
            val contentImageCount = AtomicInteger(0)
            override fun onFormResubmission(
                view: WebView?,
                dontResend: android.os.Message?,
                resend: android.os.Message?
            ) {
                resend?.sendToTarget()
            }

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
                    
                    if (!window._backBtnFixed) {
                        window._backBtnFixed = true;
                        document.addEventListener('click', function(e) {
                            var target = e.target.closest ? e.target.closest('a[href*="history.back"], #hui-back') : null;
                            if (target) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (window.NativeMangaApi && window.NativeMangaApi.goBack) {
                                    window.NativeMangaApi.goBack();
                                } else {
                                    window.history.back();
                                }
                            }
                        }, true);
                    }
                    
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
                    }
                    return isManga ? 'true' : 'false';
                })()
            """.trimIndent()

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true || url?.contains("misc.php?mod=faq") == true || url?.startsWith("data:") == true) return

                val checkUrl = url ?: ""

                val isHomepage = when (checkUrl) {
                    "https://bbs.yamibo.com/",
                    "https://bbs.yamibo.com",
                    "https://bbs.yamibo.com/?mobile=2",
                    "https://bbs.yamibo.com/?mobile=no",
                    "https://bbs.yamibo.com/index.php",
                    "https://bbs.yamibo.com/index.php?mobile=2",
                    "https://bbs.yamibo.com/index.php?mobile=no",
                    "https://bbs.yamibo.com/forum.php",
                    "https://bbs.yamibo.com/forum.php?mobile=2",
                    "https://bbs.yamibo.com/forum.php?mobile=no" -> true
                    else -> false
                }
                if (isSelected && isHomepage && view != null) {
                    view.stopLoading()
                    startLoading(view, mineUrl)
                    return
                }

                GlobalData.webProgress.value = 0
                contentImageCount.set(0)
                if (!showLoadError) {
                    hasError = false
                }
                super.onPageStarted(view, url, favicon)
                currentUrl = url

                canGoBack = evaluateCanGoBack(view)
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

                        val count = contentImageCount.getAndIncrement()

                        val isHomePage =
                            currentUrl == mineUrl || currentUrl?.contains("do=profile") == true
                        if (isHomePage) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        if (GlobalData.isDataSaverMode.value) {
                            if (count >= 3) {
                                return WebResourceResponse(
                                    "image/png",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
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
                }
                canGoBack = evaluateCanGoBack(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true || url?.contains("misc.php?mod=faq") == true) return
                super.onPageCommitVisible(view, url)

                pageTitle = view?.title ?: ""
                if (!hasError && view != null && isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    isPullRefreshing = false
                    showLoadError = false
                }
                view?.evaluateJavascript(checkSectionAndInjectJs, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                isPullRefreshing = false
                if (!hasError) {
                    showLoadError = false
                }
                super.onPageFinished(view, url)
                currentUrl = url
                if (url != null && url.startsWith("https://bbs.yamibo.com/home.php?mod=space&do=profile")) {
                    view?.clearHistory()
                }

                canGoBack = evaluateCanGoBack(view)
                view?.evaluateJavascript(checkSectionAndInjectJs, null)
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
                    hasError = true
                    isLoading = false
                    isPullRefreshing = false
                    if (retryCount == 0) {
                        showLoadError = true
                    }
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
                isPullRefreshing = false
                if (retryCount == 0) {
                    showLoadError = true
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                view?.let { WebViewPool.discard(it) }

                timeoutJob?.cancel()
                hasError = true
                isLoading = false
                isPullRefreshing = false
                showLoadError = true

                return true
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
                    isPullRefreshing = false
                    if (retryCount == 0) {
                        showLoadError = true
                    }
                }
            }
        }

        if (isSelected && (mineWebView.url == null || mineWebView.tag?.toString()
                ?.startsWith("recycled") == true || mineWebView.url == "about:blank")
        ) {
            mineWebView.tag = null
            if (savedMangaUrl != null) {
                startLoading(mineWebView, savedMangaUrl!!)
                savedMangaUrl = null
                needFallbackToHome = true
            } else {
                startLoading(mineWebView, mineUrl)
            }
        } else {
            isLoading = false
            canGoBack = evaluateCanGoBack(mineWebView)
        }
    }

    LaunchedEffect(isSelected) {
        if (!isSelected) {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
            isPullRefreshing = false
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
                    
                    var typeLabel = document.querySelector('.view_tit em');
                    if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                            window.AndroidFullscreen.notifyMangaActionDone();
                        }
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
                        if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                            window.AndroidFullscreen.notifyMangaActionDone();
                        }
                    }

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

                    var clickTimer = null;
                    var timeoutTimer = null;
                    
                    var observer = new MutationObserver(function(mutations, obs) {
                        if (document.querySelector('.pswp')) {
                            obs.disconnect();
                            clearTimeout(timeoutTimer); 
                            if (clickTimer) clearInterval(clickTimer);
                            
                            if (window.AndroidFullscreen) {
                                window.AndroidFullscreen.notify(true);
                                window.AndroidFullscreen.notifyMangaActionDone();
                            }
                            return;
                        }
                    });

                    observer.observe(document.body, { childList: true, subtree: true });

                    var clickAttempts = 0;
                    var maxClicks = 10;

                    function tryClickTarget() {
                        if (clickAttempts >= maxClicks) {
                            if (clickTimer) clearInterval(clickTimer);
                            return;
                        }
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

    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(0f) }

    SideEffect {
        if (navBarsPadding.value > lockedNavHeightValue) {
            lockedNavHeightValue = navBarsPadding.value
        }
    }
    val lockedNavHeight = lockedNavHeightValue.dp

    val statusBarsPaddingVal = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var lockedStatusHeightValue by rememberSaveable { mutableFloatStateOf(0f) }

    SideEffect {
        if (statusBarsPaddingVal.value > lockedStatusHeightValue) {
            lockedStatusHeightValue = statusBarsPaddingVal.value
        }
    }
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
                modifier = Modifier.alpha(if (isLoading && !isPullRefreshing) 0.01f else 1f),
                factory = { _ ->
                    (mineWebView.parent as? ViewGroup)?.removeView(mineWebView)
                    mineWebView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    mineWebView
                },
                update = { webView ->
                    canGoBack = evaluateCanGoBack(webView)
                    currentUrl = webView.url
                    pageTitle = webView.title ?: ""
                },
                onRelease = { webView ->
                    timeoutJob?.cancel()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    WebViewPool.release(webView)
                }
            )
            ReaderModeFAB(
                visible = canConvertToReader && !isLoading && !showLoadError && !isFullscreenState.value,
                onClick = {
                    currentUrl?.let { url ->
                        val cleanUrl = url.substringBefore("#")

                        savedMangaUrl = cleanUrl

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
                        "网页无法打开",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "页面加载失败，请检查网络后刷新",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val currentWebViewUrl = mineWebView.url
                        if (!currentWebViewUrl.isNullOrEmpty() && currentWebViewUrl != "about:blank") {
                            startLoading(mineWebView, currentWebViewUrl)
                        } else {
                            startLoading(mineWebView, mineUrl)
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

            if (isLoading && !isPullRefreshing) {
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
}