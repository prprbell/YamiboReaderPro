package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
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
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.util.history.HistoryUtil
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.ImageSaveUtil
import kotlin.text.Charsets
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner
import java.util.concurrent.atomic.AtomicInteger

class FullscreenApiOther {
    var onStateChange: ((Boolean) -> Unit)? = null
    var onMangaActionDone: (() -> Unit)? = null
    var onSaveImage: ((String) -> Unit)? = null

    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post { onStateChange?.invoke(isFullscreen) }
    }

    @JavascriptInterface
    fun notifyMangaActionDone() {
        Handler(Looper.getMainLooper()).post { onMangaActionDone?.invoke() }
    }

    @JavascriptInterface
    fun saveImage(url: String) {
        Handler(Looper.getMainLooper()).post { onSaveImage?.invoke(url) }
    }
}

private var cachedFullscreenApiOther: FullscreenApiOther? = null

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun OtherWebPage(
    url: String,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    val isDarkMode by GlobalData.isDarkMode.collectAsState()
    // 状态栏颜色：夜间主题 > 日间自定义主题 > 默认（YamiboColors.primary）
    val statusColor = darkThemeColor(YamiboColors.primary) { statusBar }
    SetStatusBarColor(statusColor)
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
    var autoOpenMangaMode by remember { mutableStateOf(false) }

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

        CookieManager.getInstance().setCookie(loadUrl, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

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
    val fullscreenApi = remember {
        if (cachedFullscreenApiOther == null) cachedFullscreenApiOther = FullscreenApiOther()
        cachedFullscreenApiOther!!
    }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }
    fullscreenApi.onSaveImage = { url ->
        AlertDialog.Builder(context)
            .setTitle("保存图片")
            .setMessage("是否保存当前图片到手机？")
            .setPositiveButton("保存") { _, _ ->
                scope.launch {
                    ImageSaveUtil.saveImage(context, url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            this.webChromeClient = webChromeClient
        }
    }
    LaunchedEffect(Unit) {
        try {
            otherWebView.onResume()
            otherWebView.resumeTimers()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.darkModeEvent.collect {
            val js = PageJsScripts.getThemeSetJs(GlobalData.isDarkMode.value, GlobalData.darkModeTheme.value, GlobalData.lightModeTheme.value)
            otherWebView.evaluateJavascript(js, null)
        }
    }

    ActivityWebViewLifecycleObserver(otherWebView)

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
            otherWebView.evaluateJavascript(PageJsScripts.CLEANUP_FULLSCREEN_JS, null)
            GlobalData.webProgress.value = 100
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false

            currentUrl?.let { threadUrl ->
                if (threadUrl.contains("mod=viewthread") && threadUrl.contains("tid=")) {
                    otherWebView.evaluateJavascript(PageJsScripts.CHECK_SECTION_JS) { result ->
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
            val contentImageCount = AtomicInteger(0)

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val link = request?.url?.toString() ?: ""
                if (link.isNotEmpty() && !link.startsWith("http://") && !link.startsWith("https://")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, link: String?): Boolean {
                val safeUrl = link ?: ""
                if (safeUrl.isNotEmpty() && !safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, link)
            }

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

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""
                val accept = request?.requestHeaders?.get("Accept") ?: ""

                if (request?.isForMainFrame == true &&
                    request.method == "GET" &&
                    (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) &&
                    urlStr.contains("bbs.yamibo.com")
                ) {
                    val html = YamiboRetrofit.proxyHtmlForDarkMode(request)
                    if (html != null) {
                        val modified = PageJsScripts.injectThemeCssIntoHtml(html, GlobalData.isDarkMode.value, GlobalData.darkModeTheme.value, GlobalData.lightModeTheme.value)
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            modified.byteInputStream(Charsets.UTF_8)
                        )
                    }
                }

                val isImage = accept.contains("image/", ignoreCase = true) ||
                        urlStr.contains(
                            Regex(
                                "\\.(jpg|jpeg|png|webp|gif)",
                                RegexOption.IGNORE_CASE
                            )
                        ) ||
                        urlStr.contains("attachment")

                if (request?.isForMainFrame == false && request.method == "GET" && urlStr.contains("yamibo.com")) {
                    if (isImage) {
                        if (!urlStr.contains("smiley") && !urlStr.contains("avatar") &&
                            !urlStr.contains("common") && !urlStr.contains("static/image") &&
                            !urlStr.contains("template") && !urlStr.contains("block")
                        ) {
                            contentImageCount.getAndIncrement()

                            val headers = mutableMapOf<String, String>()
                            request.requestHeaders?.forEach { (k, v) -> headers[k] = v }

                            val coilResponse =
                                org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy.interceptImage(
                                    context,
                                    urlStr,
                                    headers
                                )
                            if (coilResponse != null) return coilResponse

                            val proxyResponse = YamiboRetrofit.proxyWebViewResource(request)
                            if (proxyResponse != null) return proxyResponse

                            return WebResourceResponse(
                                "image/jpeg",
                                "utf-8",
                                404,
                                "Blocked by Interceptor",
                                null,
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    } else {
                        val proxyResponse = YamiboRetrofit.proxyWebViewResource(request)
                        if (proxyResponse != null) return proxyResponse
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, pageUrl, favicon)
                if (url == "about:blank" || url.contains("warmup=true")) return
                GlobalData.webProgress.value = 0
                contentImageCount.set(0)
                isLoading = true
                currentUrl = pageUrl

                if (view != null) {
                    val list = view.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                }

                view?.evaluateJavascript(PageJsScripts.OTHER_WEB_HIDE_COMMAND, null)
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
                if (!isLoading) {
                    GlobalData.webProgress.value = 100
                }
            }

            override fun onPageCommitVisible(view: WebView?, commitUrl: String?) {
                if (commitUrl == "about:blank" || commitUrl?.contains("warmup=true") == true) return
                super.onPageCommitVisible(view, commitUrl)

                view?.evaluateJavascript(PageJsScripts.OTHER_WEB_INIT_PSWP_JS, null)
                view?.evaluateJavascript(PageJsScripts.PJAX_FALLBACK_JS, null)
                view?.evaluateJavascript(PageJsScripts.THREAD_LIST_CLICK_FIX_JS, null)

                if (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) {
                    view?.evaluateJavascript(
                        PageJsScripts.getThemeSetJs(GlobalData.isDarkMode.value, GlobalData.darkModeTheme.value, GlobalData.lightModeTheme.value), null
                    )
                }

                if (isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    showLoadError = false
                }

                if (commitUrl != null && HistoryUtil.isThreadUrl(commitUrl)) {
                    view?.evaluateJavascript(PageJsScripts.EXTRACT_THREAD_INFO_JS) { jsonStr ->
                        try {
                            val cleanJson = if (jsonStr?.startsWith("\"") == true) JSON.parse(jsonStr) as String else jsonStr
                            val obj = JSON.parseObject(cleanJson)
                            val title = obj.getString("title") ?: ""
                            val author = obj.getString("author") ?: ""
                            val section = obj.getString("section") ?: ""
                            if (title.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    HistoryUtil.addOrUpdateHistory(commitUrl, title, author, section)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
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
                            val threadMatch = Regex("thread-\\d+-(\\d+)").find(urlStr)
                            if (threadMatch != null) {
                                page = threadMatch.groupValues[1].toIntOrNull() ?: 1
                            }
                        }
                    }
                    page
                }
                val currentPageNum = extractPage(finishedUrl)

                view?.evaluateJavascript(PageJsScripts.OTHER_WEB_CHECK_TYPE_JS) { result ->
                    val typeCode = result?.toIntOrNull() ?: 3

                    scope.launch(Dispatchers.IO) {
                        val map = FavoriteUtil.getFavoriteMapSuspend()
                        map[url]?.let { fav ->
                            var changed = false
                            var newFav = fav

                            if (fav.type != typeCode) {
                                newFav = newFav.copy(type = typeCode)
                                changed = true
                            }

                            if (fav.lastView != currentPageNum) {
                                newFav = newFav.copy(lastView = currentPageNum)
                                changed = true
                            }

                            if (changed) {
                                map[url] = newFav
                                FavoriteUtil.saveFavoriteOrder(map.values.toList())
                            }
                        }
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

        if (otherWebView.url == null || otherWebView.tag?.toString()
                ?.startsWith("recycled") == true || otherWebView.url == "about:blank"
        ) {
            otherWebView.tag = null

            scope.launch(Dispatchers.IO) {
                val map = FavoriteUtil.getFavoriteMapSuspend()
                val lastSavedPage = map[url]?.lastView ?: 1
                val startUrl = getPagedUrl(finalUrl, lastSavedPage)

                withContext(Dispatchers.Main) {
                    startLoading(otherWebView, startUrl)
                }
            }
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            otherWebView.evaluateJavascript(PageJsScripts.OTHER_WEB_AUTO_OPEN_JS, null)
            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                otherWebView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
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
    if (statusBarsPaddingVal.value > lockedStatusHeightValue) lockedStatusHeightValue =
        statusBarsPaddingVal.value
    val lockedStatusHeight = lockedStatusHeightValue.dp

    val isFullscreen = isFullscreenState.value || autoOpenMangaMode
    val topSpacerColor = if (isFullscreen) Color.Black else darkThemeColor(YamiboColors.primary) { statusBar }

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
                    (it.parent as? ViewGroup)?.removeView(it)
                    try {
                        it.onPause()
                        WebViewPool.release(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
                    CircularProgressIndicator(color = darkModeColor(YamiboColors.secondary, YamiboColors.secondaryDark))
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