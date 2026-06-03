package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper

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
import androidx.compose.runtime.derivedStateOf
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
import org.shirakawatyu.yamibo.novel.network.NovelApi
import org.shirakawatyu.yamibo.novel.util.reader.CacheData
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.ImageSaveUtil
import kotlin.text.Charsets
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.reader.ReaderModeDetector
import org.shirakawatyu.yamibo.novel.util.reader.ReaderReturnBridge
import org.shirakawatyu.yamibo.novel.util.reader.ReaderMemoryPrewarmManager
import org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.shirakawatyu.yamibo.novel.util.StaticAssetProxy
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val pstatusFormat = SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault())

private fun parsePstatusTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        pstatusFormat.parse(text.trim())?.time
    } catch (_: Exception) {
        null
    }
}

class FullscreenApiReader {
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

private var cachedFullscreenApiReader: FullscreenApiReader? = null

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ReaderWebPage(
    url: String,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    val isDarkMode by GlobalData.isDarkMode.collectAsState()
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
    var pageTitle by remember { mutableStateOf("") }
    var autoOpenMangaMode by remember { mutableStateOf(false) }
    var pendingOriginalPostRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingOriginalPostTargetUrl by remember { mutableStateOf<String?>(null) }

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
        val targetUrl = loadUrl.substringBefore("#")

        timeoutJob?.cancel()
        isLoading = true
        showLoadError = false
        retryCount = 0
        currentUrl = targetUrl

        CookieManager.getInstance().setCookie(targetUrl, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

        fun retryTargetOnce() {
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(10000)
                if (isLoading) {
                    webView.stopLoading()
                    isLoading = false
                    showLoadError = true
                }
            }

            webView.loadUrl(targetUrl)
        }

        timeoutJob = scope.launch {
            delay(10000)
            if (isLoading) {
                webView.stopLoading()
                retryCount++

                retryTargetOnce()
            }
        }

        webView.loadUrl(targetUrl)
    }

    data class ReaderWebProbe(
        val url: String,
        val tid: String?,
        val page: Int,
        val authorId: String?,
        val isAuthorOnly: Boolean
    )

    fun parseReaderWebProbe(jsonStr: String?): ReaderWebProbe? {
        return try {
            val cleanJson = if (jsonStr?.startsWith("\"") == true) JSON.parse(jsonStr) as String else jsonStr
            val obj = JSON.parseObject(cleanJson)
            ReaderWebProbe(
                url = obj.getString("url") ?: currentUrl ?: finalUrl,
                tid = obj.getString("tid") ?: ReaderReturnBridge.extractTid(currentUrl ?: finalUrl),
                page = obj.getIntValue("page").takeIf { it > 0 } ?: ReaderReturnBridge.extractPage(currentUrl ?: finalUrl),
                authorId = obj.getString("authorId") ?: ReaderReturnBridge.extractAuthorId(currentUrl ?: finalUrl),
                isAuthorOnly = obj.getBooleanValue("authorOnly")
            )
        } catch (_: Exception) {
            null
        }
    }

    val readerWebProbeJs = remember {
        """
        (function() {
            function queryParam(name) {
                var m = new RegExp('[?&]' + name + '=([^&#]+)').exec(location.href);
                return m ? decodeURIComponent(m[1]) : null;
            }
            function extractTid(url) {
                var m = /[?&](?:tid|ptid)=(\d+)/.exec(url) || /thread-(\d+)-/.exec(url);
                return m ? m[1] : null;
            }
            function extractPage(url) {
                var m = /[?&]page=(\d+)/.exec(url) || /thread-\d+-(\d+)-/.exec(url);
                return m ? Math.max(1, parseInt(m[1], 10) || 1) : 1;
            }
            var hasShowAllFloorsLink = !!document.querySelector('a[rel="nofollow"][href^="thread-"], a[href*="显示全部楼层"]');
            var authorId = queryParam('authorid');
            return JSON.stringify({
                url: location.href,
                tid: extractTid(location.href),
                page: extractPage(location.href),
                authorId: authorId,
                authorOnly: !!authorId || hasShowAllFloorsLink
            });
        })();
        """
    }

    val isFullscreenState = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            // 和 BBSPage 保持一致：如果 OtherWebPage 是被 ReaderPage / NativeMangaPage 覆盖，
            // 不要在 dispose 时恢复系统栏。否则会把 ReaderPage 刚隐藏的顶部系统栏重新拉出来。
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
    val fullscreenApi = remember {
        if (cachedFullscreenApiReader == null) cachedFullscreenApiReader = FullscreenApiReader()
        cachedFullscreenApiReader!!
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
    val readerWebView = remember {
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
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                blockNetworkImage = false
            }
            addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            this.webChromeClient = webChromeClient
        }
    }
    LaunchedEffect(Unit) {
        try {
            readerWebView.onResume()
            readerWebView.resumeTimers()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.darkModeEvent.collect {
            val js = PageJsScripts.getThemeSetJs(
                GlobalData.isDarkMode.value,
                GlobalData.darkModeTheme.value,
                GlobalData.lightModeTheme.value
            )
            readerWebView.evaluateJavascript(js, null)
        }
    }

    ActivityWebViewLifecycleObserver(readerWebView)

    val canConvertToReader by remember(currentUrl, pageTitle) {
        derivedStateOf {
            ReaderModeDetector.canConvertToReaderMode(currentUrl ?: readerWebView.url, pageTitle.ifBlank { readerWebView.title })
        }
    }

    fun buildReaderMemoryPrewarmTarget(probe: ReaderWebProbe?): ReaderMemoryPrewarmManager.Target? {
        val bridge = ReaderReturnBridge.context ?: return null
        val pageUrl = (probe?.url ?: currentUrl ?: readerWebView.url ?: finalUrl).substringBefore("#")
        val currentTid = probe?.tid ?: ReaderReturnBridge.extractTid(pageUrl) ?: return null
        val bridgeTid = bridge.tid ?: ReaderReturnBridge.extractTid(bridge.readerUrl)
        if (bridgeTid != null && bridgeTid != currentTid) return null

        // 用户在看全部帖子（退出了只看楼主），此时 ReaderPage 已有当前页的内存缓存，无需预热。
        if (bridge.authorId != null && probe?.isAuthorOnly == false) return null

        val page = (probe?.page ?: ReaderReturnBridge.extractPage(pageUrl)).coerceAtLeast(1)
        val authorId = bridge.authorId
            ?: probe?.authorId
            ?: ReaderReturnBridge.extractAuthorId(pageUrl)
            ?: return null

        val primaryUrl = ReaderMemoryPrewarmManager.canonicalReaderUrl(bridge.readerUrl)
        val aliasUrls = listOf(
            bridge.readerUrl,
            ReaderReturnBridge.toAbsoluteBbsUrl(bridge.readerUrl),
            pageUrl,
            ReaderReturnBridge.toAbsoluteBbsUrl(pageUrl),
            ReaderMemoryPrewarmManager.canonicalReaderUrl(pageUrl)
        )
            .map { it.trim() }
            .filter { it.isNotBlank() && it != primaryUrl }
            .distinct()

        return ReaderMemoryPrewarmManager.Target(
            primaryUrl = primaryUrl,
            tid = currentTid,
            page = page,
            authorId = authorId,
            aliasUrls = aliasUrls
        )
    }

    @Suppress("UNUSED_VARIABLE", "UnusedReturnValue")
    fun prewarmReaderMemoryCacheIfNeeded(probe: ReaderWebProbe?) {
        val target = buildReaderMemoryPrewarmTarget(probe) ?: return
        ReaderMemoryPrewarmManager.prewarmIfNeeded(context.applicationContext, target)
    }

    fun openReaderFromOtherWeb(probe: ReaderWebProbe?) {
        val cleanUrl = (probe?.url ?: currentUrl ?: readerWebView.url ?: finalUrl).substringBefore("#")
        val currentTid = probe?.tid ?: ReaderReturnBridge.extractTid(cleanUrl)
        val bridge = ReaderReturnBridge.context
        val sameAsReaderContext = bridge != null &&
                currentTid != null &&
                bridge.tid != null &&
                bridge.tid == currentTid

        val targetWebPage = (probe?.page ?: ReaderReturnBridge.extractPage(cleanUrl)).coerceAtLeast(1)

        val readerBaseUrl = if (sameAsReaderContext) {
            bridge!!.readerUrl
        } else {
            ReaderModeDetector.extractThreadPath(cleanUrl) ?: cleanUrl
        }
        val targetAuthorId = if (sameAsReaderContext) bridge!!.authorId else probe?.authorId
        val targetReaderUrl = ReaderReturnBridge.buildReaderUrl(readerBaseUrl, targetWebPage, targetAuthorId)

        val shouldReturnToExistingReader = sameAsReaderContext &&
                navController.previousBackStackEntry?.destination?.route?.startsWith("ReaderPage") == true
        val targetReaderPageIndex = when {
            sameAsReaderContext && targetWebPage == bridge!!.readerWebPage -> bridge.readerPageIndex
            else -> null
        }

        ReaderReturnBridge.requestReaderJump(
            tid = currentTid,
            readerUrl = targetReaderUrl,
            webPage = targetWebPage,
            readerPageIndex = targetReaderPageIndex,
            // 只有命中同一个 Reader 上下文时，才复用旧 Reader 的缓存身份。
            // 普通 OtherWebPage FAB 入口仍然只阅读，不开放磁盘缓存，避免产生难管理的无标题缓存。
            allowCache = sameAsReaderContext,
            cacheTitle = if (sameAsReaderContext) bridge?.cacheTitle else null
        )

        // 进入 ReaderPage 前先把系统栏/底栏切到阅读器期望状态，
        // 避免 OtherWebPage 的普通网页状态在转场期间闪回。
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        bottomNavBarVM.setBottomNavBarVisibility(false)

        if (shouldReturnToExistingReader) {
            navController.navigateUp()
        } else {
            navController.navigate("ReaderPage/${ReaderReturnBridge.encodeRouteArg(targetReaderUrl)}") {
                navController.currentDestination?.id?.let { currentId ->
                    popUpTo(currentId) {
                        inclusive = true
                    }
                }
            }
        }
    }

    val pendingOriginalPostRequest = ReaderReturnBridge.originalPostRequest
    LaunchedEffect(pendingOriginalPostRequest?.id, readerWebView) {
        val request = pendingOriginalPostRequest ?: return@LaunchedEffect
        val currentTid = ReaderReturnBridge.extractTid(readerWebView.url ?: currentUrl ?: finalUrl)
        if (request.tid == null || currentTid == null || request.tid == currentTid) {
            pendingOriginalPostRequestId = request.id
            pendingOriginalPostTargetUrl = request.targetUrl

            if (!ReaderReturnBridge.sameUrlIgnoringHashAndTrailingSlash(readerWebView.url, request.targetUrl)) {
                startLoading(readerWebView, request.targetUrl)
            } else {
                ReaderReturnBridge.clearOriginalPostRequest(request.id)
                pendingOriginalPostRequestId = null
                pendingOriginalPostTargetUrl = null
                isLoading = false
                showLoadError = false
            }
        }
    }

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
            readerWebView.evaluateJavascript(PageJsScripts.CLEANUP_FULLSCREEN_JS, null)
            GlobalData.webProgress.value = 100
        } else {
            if (autoOpenMangaMode) autoOpenMangaMode = false

            currentUrl?.let { threadUrl ->
                if (threadUrl.contains("mod=viewthread") && threadUrl.contains("tid=")) {
                    readerWebView.evaluateJavascript(PageJsScripts.CHECK_SECTION_JS) { result ->
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
                            val pageTitle = readerWebView.title ?: ""
                            readerWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                                val cleanHtml = try {
                                    JSON.parse(htmlResult) as? String ?: ""
                                } catch (_: Exception) {
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

    var isHistoryCleared by remember { mutableStateOf(false) }

    LaunchedEffect(readerWebView) {
        readerWebView.webViewClient = object : YamiboWebViewClient() {
            val contentImageCount = AtomicInteger(0)

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val link = request?.url?.toString() ?: ""
                if (link.isBlank()) return false

                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    return try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!BBSGlobalWebViewClient.isYamiboUrl(link)) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    } catch (_: Exception) {
                    }
                    return true
                }

                return super.shouldOverrideUrlLoading(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, link: String?): Boolean {
                val safeUrl = link ?: ""
                if (safeUrl.isBlank()) return false

                if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
                    return try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!BBSGlobalWebViewClient.isYamiboUrl(safeUrl)) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
                    } catch (_: Exception) {
                    }
                    return true
                }

                return super.shouldOverrideUrlLoading(view, safeUrl)
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
                        val modified = PageJsScripts.injectThemeCssIntoHtml(
                            html,
                            GlobalData.isDarkMode.value,
                            GlobalData.darkModeTheme.value,
                            GlobalData.lightModeTheme.value
                        )
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            modified.byteInputStream(Charsets.UTF_8)
                        )
                    }
                }

                StaticAssetProxy.tryProxySafeStaticAsset(request)?.let { return it }

                val isImage = accept.contains("image/", ignoreCase = true) ||
                        urlStr.contains(
                            Regex(
                                "\\.(jpg|jpeg|png|webp|gif)",
                                RegexOption.IGNORE_CASE
                            )
                        ) ||
                        urlStr.contains("attachment")

                if (request?.isForMainFrame == false &&
                    request.method == "GET" &&
                    urlStr.contains("yamibo.com") &&
                    isImage
                ) {
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
                pageTitle = view?.title ?: pageTitle

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

                val target = pendingOriginalPostTargetUrl
                val requestId = pendingOriginalPostRequestId
                if (requestId != null && target != null &&
                    ReaderReturnBridge.sameUrlIgnoringHashAndTrailingSlash(commitUrl, target)
                ) {
                    ReaderReturnBridge.clearOriginalPostRequest(requestId)
                    pendingOriginalPostRequestId = null
                    pendingOriginalPostTargetUrl = null
                }

                view?.evaluateJavascript(PageJsScripts.OTHER_COMMIT_BOOTSTRAP_JS, null)

                if (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) {
                    view?.evaluateJavascript(
                        PageJsScripts.getThemeSetJs(
                            GlobalData.isDarkMode.value,
                            GlobalData.darkModeTheme.value,
                            GlobalData.lightModeTheme.value
                        ), null
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
                pageTitle = view?.title ?: pageTitle

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

                // 静默预热内存缓存
                scope.launch {
                    delay(1000)
                    if (view != null) {
                        view.evaluateJavascript(readerWebProbeJs) { result ->
                            prewarmReaderMemoryCacheIfNeeded(parseReaderWebProbe(result))
                        }
                    } else {
                        prewarmReaderMemoryCacheIfNeeded(null)
                    }
                }

                // 后台刷新磁盘缓存：对比帖子最后编辑时间，只在有更新时才拉取
                val pendingRefresh = ReaderReturnBridge.pendingCacheRefresh
                if (pendingRefresh != null && view != null) {
                    ReaderReturnBridge.pendingCacheRefresh = null
                    scope.launch(Dispatchers.IO) {
                        val cachedPage = LocalCacheUtil.getInstance(context)
                            .getCachedPages(pendingRefresh.url)
                            .find { it.pageNum == pendingRefresh.pageNum }
                        if (cachedPage == null) return@launch

                        delay(3000)
                        val pstatusTime = withContext(Dispatchers.Main) {
                            suspendCancellableCoroutine { cont ->
                                view.evaluateJavascript(
                                    "(function(){var els=document.querySelectorAll('i.pstatus');var times=[];for(var i=0;i<els.length;i++){var m=els[i].textContent.match(/(\\d{4}-\\d{1,2}-\\d{1,2}\\s*\\d{1,2}:\\d{2})/);if(m)times.push(m[1])}return times.join('|')})();"
                                ) { result ->
                                    val raw = try {
                                        JSON.parse(result) as? String ?: ""
                                    } catch (_: Exception) {
                                        result?.trim('"') ?: ""
                                    }
                                    cont.resume(raw)
                                }
                            }
                        }
                        val latestEditTime = pstatusTime.split("|")
                            .mapNotNull { parsePstatusTime(it) }
                            .maxOrNull()
                        if (latestEditTime == null || cachedPage.timestamp >= latestEditTime) return@launch

                        val tid = ReaderReturnBridge.extractTid(pendingRefresh.url)
                            ?: pendingRefresh.url.substringAfter("tid=").substringBefore("&")
                        if (tid.isBlank() || pendingRefresh.authorId == null) return@launch
                        try {
                            val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
                            val resp = api.getThreadPageByAuthor(tid, pendingRefresh.pageNum, pendingRefresh.authorId)
                            val json = JSON.parseObject(resp.string())
                            val variables = json.getJSONObject("Variables")
                            val postlist = variables.getJSONArray("postlist")
                            val messages = (0 until postlist.size).map { i ->
                                postlist.getJSONObject(i).getString("message")
                            }
                            val combinedHtml = messages.joinToString("") {
                                "<div class=\"message\">$it</div>"
                            }
                            val cacheData = CacheData(
                                cachedPageNum = pendingRefresh.pageNum,
                                htmlContent = combinedHtml,
                                maxPageNum = 1,
                                authorId = pendingRefresh.authorId
                            )
                            LocalCacheUtil.getInstance(context).savePage(
                                pendingRefresh.url, pendingRefresh.pageNum, cacheData,
                                false, pendingRefresh.cacheTitle
                            )
                        } catch (_: Exception) { }
                    }
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

                scope.launch(Dispatchers.IO) {
                    val map = FavoriteUtil.getFavoriteMapSuspend()
                    val identityUrl = FavoriteUtil.normalizeUrl(url)
                    map[identityUrl]?.let { fav ->
                        if (fav.lastView != currentPageNum) {
                            map[identityUrl] = fav.copy(lastView = currentPageNum)
                            FavoriteUtil.saveFavoriteOrder(map.values.toList())
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
                    val errorUrl = request.url?.toString() ?: ""
                    val currentUrl = view?.url ?: ""
                    if ((errorUrl.isEmpty() || errorUrl == currentUrl) &&
                        BBSGlobalWebViewClient.isYamiboUrl(errorUrl)
                    ) {
                        timeoutJob?.cancel()
                        isLoading = false
                        if (retryCount == 0) showLoadError = true
                    }
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val errorUrl = request.url?.toString() ?: ""
                    val currentUrl = view?.url ?: ""
                    if ((errorUrl.isEmpty() || errorUrl == currentUrl) &&
                        BBSGlobalWebViewClient.isYamiboUrl(errorUrl)
                    ) {
                        timeoutJob?.cancel()
                        isLoading = false
                        if (retryCount == 0) showLoadError = true
                    }
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }

        if (readerWebView.url == null || readerWebView.tag?.toString()
                ?.startsWith("recycled") == true || readerWebView.url == "about:blank"
        ) {
            readerWebView.tag = null

            scope.launch(Dispatchers.IO) {
                val map = FavoriteUtil.getFavoriteMapSuspend()
                val identityUrl = FavoriteUtil.normalizeUrl(url)
                val lastSavedPage = map[identityUrl]?.lastView ?: 1
                val startUrl = getPagedUrl(finalUrl, lastSavedPage)

                withContext(Dispatchers.Main) {
                    startLoading(readerWebView, startUrl)
                }
            }
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            readerWebView.evaluateJavascript(PageJsScripts.OTHER_WEB_AUTO_OPEN_JS, null)
            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                readerWebView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
            }
        }
    }

    BackHandler(enabled = true) {
        if (canGoBack) {
            readerWebView.goBack()
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
                    (readerWebView.parent as? ViewGroup)?.removeView(readerWebView)
                    readerWebView
                },
                update = {
                    val list = it.copyBackForwardList()
                    if (baseIndex != -1 && list.currentIndex < baseIndex) {
                        baseIndex = list.currentIndex
                    }
                    canGoBack = baseIndex != -1 && list.currentIndex > baseIndex
                    currentUrl = it.url
                    pageTitle = it.title ?: pageTitle
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

            ReaderModeFAB(
                visible = canConvertToReader && !isLoading && !showLoadError && !isFullscreenState.value && !autoOpenMangaMode,
                onClick = {
                    readerWebView.evaluateJavascript(readerWebProbeJs) { result ->
                        openReaderFromOtherWeb(parseReaderWebProbe(result))
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
                    Icon(Icons.Default.Warning, "加载失败", Modifier.size(48.dp), Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("页面加载失败", fontSize = 18.sp, color = Color.DarkGray)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        startLoading(
                            readerWebView,
                            readerWebView.url ?: url
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
