package org.shirakawatyu.yamibo.novel.ui.page

import org.shirakawatyu.yamibo.novel.util.PageJsScripts

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.MinePageVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.AccountSyncManager
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ReaderModeDetector
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
private var mineWebViewPauseRunnable: Runnable? = null
private val mineWebViewHandler = Handler(Looper.getMainLooper())
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
    val minePageVM: MinePageVM = viewModel(viewModelStoreOwner = context as ComponentActivity)
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
        val isNew = minePageVM.cachedWebView == null
        val webView = minePageVM.getOrAcquireWebView(context)

        if (isNew) {
            webView.settings.apply {
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true
                loadsImagesAutomatically = true
                blockNetworkImage = false
            }
            webView.addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            webView.addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            webView.webChromeClient = webChromeClient
        } else {
            webView.removeJavascriptInterface("AndroidFullscreen")
            webView.removeJavascriptInterface("NativeMangaApi")
            webView.addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            webView.addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            webView.webChromeClient = webChromeClient
        }
        webView
    }

    DisposableEffect(mineWebView) {
        onDispose {
            minePageVM.scheduleRelease()
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
                if (!curl.isNullOrEmpty() && curl != "about:blank" && !curl.contains("warmup=true")) {
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
                mineWebView.evaluateJavascript(PageJsScripts.RELOAD_BROKEN_IMAGES_JS, null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        mineWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
            scope.launch(Dispatchers.Default) {
                val cleanHtml = try {
                    JSON.parse(htmlResult) as? String ?: ""
                } catch (_: Exception) {
                    htmlResult?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                }

                val urls = urlsJoined.split("|||").filter { it.isNotBlank() }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempMangaIndex = clickedIndex
                    GlobalData.tempHtml = cleanHtml
                    GlobalData.tempTitle = title
                    mineWebView.evaluateJavascript(PageJsScripts.FREEZE_BROKEN_IMAGES_JS, null)
                    mineWebView.evaluateJavascript("window.stop();", null)
                    mineWebView.stopLoading()
                    mineWebView.onPause()


                    autoOpenMangaMode = false
                    val passUrl = currentUrl ?: "https://bbs.yamibo.com/forum.php"

                    savedMangaUrl = passUrl
                    val encodedUrl = URLEncoder.encode(passUrl, "utf-8")
                    val encodedOriginal = URLEncoder.encode(passUrl, "utf-8")
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
            mineWebView.evaluateJavascript(PageJsScripts.CLEANUP_FULLSCREEN_JS, null)
        } else {
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
            }
            currentUrl?.let { url ->
                if (url.contains("mod=viewthread") && url.contains("tid=")) {
                    mineWebView.evaluateJavascript(PageJsScripts.CHECK_SECTION_JS) { result ->
                        val sectionName = try {
                            JSON.parse(result) as? String ?: ""
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
                                    JSON.parse(htmlResult) as? String ?: ""
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
            private fun isHomepageUrl(url: String): Boolean {
                return when (url) {
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
            }
            override fun onFormResubmission(
                view: WebView?,
                dontResend: android.os.Message?,
                resend: android.os.Message?
            ) {
                resend?.sendToTarget()
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val urlStr = request?.url?.toString() ?: ""

                if (isSelected && isHomepageUrl(urlStr) && view != null) {
                    scope.launch(Dispatchers.IO) {
                        delay(500L)
                        AccountSyncManager.syncCookieAndCheckSign(context, "LOGIN_REDIRECT_INTERCEPT")
                    }
                    startLoading(view, mineUrl)
                    return true
                }

                return super.shouldOverrideUrlLoading(view, request)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true || url?.startsWith("data:") == true) return

                val checkUrl = url ?: ""

                val isHomePage = isHomepageUrl(checkUrl) || checkUrl == mineUrl || checkUrl.contains("do=profile")
                bottomNavBarVM.isMineAtRoot = isHomePage

                if (isSelected && isHomepageUrl(checkUrl) && view != null) {
                    view.stopLoading()
                    scope.launch(Dispatchers.IO) {
                        delay(500L)
                        AccountSyncManager.syncCookieAndCheckSign(context, "LOGIN_REDIRECT_INTERCEPT")
                    }
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
                        !urlStr.contains("common") && !urlStr.contains("static/image") &&
                        !urlStr.contains("template") && !urlStr.contains("block")
                    ) {
                        val count = contentImageCount.getAndIncrement()

                        if (request.method == "GET" && urlStr.contains("yamibo.com")) {
                            val headers = mutableMapOf<String, String>()
                            request.requestHeaders?.forEach { (k, v) -> headers[k] = v }

                            val coilResponse = CoilWebViewProxy.interceptImage(context, urlStr, headers)
                            if (coilResponse != null) return coilResponse

                            val proxyResponse = YamiboRetrofit.proxyWebViewResource(request)
                            if (proxyResponse != null) return proxyResponse

                            return WebResourceResponse(
                                "image/jpeg",
                                "utf-8",
                                404,
                                "Blocked by Interceptor",
                                null,
                                ByteArrayInputStream(ByteArray(0))
                            )
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

                val checkUrl = url ?: ""
                val isHomePage = isHomepageUrl(checkUrl) || checkUrl == mineUrl || checkUrl.contains("do=profile")
                bottomNavBarVM.isMineAtRoot = isHomePage
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true) return
                super.onPageCommitVisible(view, url)

                pageTitle = view?.title ?: ""
                if (!hasError && view != null && isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    isPullRefreshing = false
                    showLoadError = false
                }
                // 使用外部提取的特定于 MinePage 的脚本常量
                view?.evaluateJavascript(PageJsScripts.MINE_INJECT_PSWP_AND_MANGA_JS, null)
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
                // 使用外部提取的特定于 MinePage 的脚本常量
                view?.evaluateJavascript(PageJsScripts.MINE_INJECT_PSWP_AND_MANGA_JS, null)
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
        if (isSelected) {
            mineWebViewPauseRunnable?.let {
                mineWebViewHandler.removeCallbacks(it)
                mineWebViewPauseRunnable = null
            }
            try {
                mineWebView.onResume()
                mineWebView.resumeTimers()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
            isPullRefreshing = false
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            mineWebView.evaluateJavascript(PageJsScripts.AUTO_OPEN_MANGA_JS, null)

            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                mineWebView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
            }
        }
    }

    BackHandler(enabled = true) {
        val checkUrl = currentUrl ?: mineWebView.url ?: ""
        val isAtMineHome = checkUrl.contains("mod=space") && checkUrl.contains("do=profile")
        when {
            needFallbackToHome -> {
                needFallbackToHome = false
                timeoutJob?.cancel()
                startLoading(mineWebView, mineUrl)
            }
            evaluateCanGoBack(mineWebView) -> {
                timeoutJob?.cancel()
                mineWebView.goBack()
            }
            !isAtMineHome -> {
                timeoutJob?.cancel()
                startLoading(mineWebView, mineUrl)
            }
            else -> {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    activity?.finish()
                }
            }
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
                modifier = Modifier,
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
                onRelease = { _ ->
                    timeoutJob?.cancel()

                    mineWebViewPauseRunnable?.let {
                        mineWebViewHandler.removeCallbacks(it)
                    }
                    val runnable = Runnable {
                        try {
                            mineWebView.onPause()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    mineWebViewPauseRunnable = runnable
                    mineWebViewHandler.postDelayed(runnable, 3000L)
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