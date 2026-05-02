package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.state.BBSPageState
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.BbsSkeletonScreen
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.network.NetworkMonitor
import org.shirakawatyu.yamibo.novel.util.reader.ReaderModeDetector
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

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


class BBSGlobalWebViewClient(private val context: Context) : YamiboWebViewClient() {
    private val contentImageCount = AtomicInteger(0)

    companion object {
        const val INDEX_URL = "https://bbs.yamibo.com/forum.php"
        const val MOBILE_INDEX_URL = "https://bbs.yamibo.com/forum.php?mobile=2"
        const val BBS_URL = "https://bbs.yamibo.com/index.php?mobile=2"
        const val BASE_BBS_URL = "https://bbs.yamibo.com/"
        private val IMAGE_EXT_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)
    }

    fun forceInjectMangaJs(webView: WebView) {
        webView.evaluateJavascript(PageJsScripts.INJECT_PSWP_AND_MANGA_JS, null)
        webView.evaluateJavascript(PageJsScripts.FIX_CAROUSEL_LAYOUT_JS, null)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        GlobalData.webProgress.value = 0
        contentImageCount.set(0)
        super.onPageStarted(view, url, favicon)
        val safeUrl = url ?: ""
        val isHomepage =
            safeUrl == INDEX_URL || safeUrl == BBS_URL || safeUrl == BASE_BBS_URL || safeUrl == MOBILE_INDEX_URL ||
                    (safeUrl.startsWith("https://bbs.yamibo.com/forum.php") && !safeUrl.contains("mod="))
        (context as? ComponentActivity)?.let { activity ->
            val navBarVM =
                androidx.lifecycle.ViewModelProvider(activity)[BottomNavBarVM::class.java]
            navBarVM.isBbsAtRoot = isHomepage
        }

        BBSPageState.currentUrl = url
        BBSPageState.isLoading = true
        BBSPageState.showLoadError = false
        BBSPageState.isErrorState = false
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val safeUrl = url ?: ""
        val isHomepage =
            safeUrl == INDEX_URL || safeUrl == BBS_URL || safeUrl == BASE_BBS_URL || safeUrl == MOBILE_INDEX_URL ||
                    (safeUrl.startsWith("https://bbs.yamibo.com/forum.php") && !safeUrl.contains("mod="))
        (context as? ComponentActivity)?.let { activity ->
            val navBarVM =
                androidx.lifecycle.ViewModelProvider(activity)[BottomNavBarVM::class.java]
            navBarVM.isBbsAtRoot = isHomepage
        }
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
                urlStr.contains(IMAGE_EXT_REGEX) ||
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

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        view?.evaluateJavascript(PageJsScripts.INJECT_PSWP_AND_MANGA_JS, null)
        view?.evaluateJavascript(PageJsScripts.FIX_CAROUSEL_LAYOUT_JS, null)

        BBSPageState.pageTitle = view?.title ?: ""
        if (!BBSPageState.isErrorState) {
            BBSPageState.isLoading = false
            BBSPageState.showLoadError = false
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val isHomepage =
            url == INDEX_URL || url == BBS_URL || url == BASE_BBS_URL || url == MOBILE_INDEX_URL
        if (isHomepage) {
            view?.clearHistory()
        }
        view?.evaluateJavascript(PageJsScripts.INJECT_PSWP_AND_MANGA_JS, null)
        view?.evaluateJavascript(PageJsScripts.FIX_CAROUSEL_LAYOUT_JS, null)

        BBSPageState.isLoading = false

        if (!BBSPageState.isErrorState) {
            BBSPageState.showLoadError = false
            if (url != null && !url.contains("about:blank")) {
                BBSPageState.hasSuccessfullyLoaded = true
            }
        }
        checkAndUpdateLoginState()
    }

    private fun checkAndUpdateLoginState() {
        val cookieManager = CookieManager.getInstance()
        val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""
        val currentLoginState = currentCookie.contains("EeqY_2132_auth=")

        BBSPageState.lastLoginState = currentLoginState
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        handleErrorState()
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            handleErrorState()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        handleErrorState()
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            handleErrorState()
        }
    }

    private fun handleErrorState() {
        BBSPageState.hasSuccessfullyLoaded = false
        BBSPageState.isErrorState = true
        BBSPageState.isLoading = false
        BBSPageState.showLoadError = true
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("RestrictedApi", "JavascriptInterface")
@Composable
fun BBSPage(
    webView: WebView,
    isSelected: Boolean,
    navController: NavController
) {
    val indexUrl = BBSGlobalWebViewClient.INDEX_URL
    val mobileIndexUrl = BBSGlobalWebViewClient.MOBILE_INDEX_URL
    val bbsUrl = BBSGlobalWebViewClient.BBS_URL
    val baseBbsUrl = BBSGlobalWebViewClient.BASE_BBS_URL

    val activity = LocalContext.current as? ComponentActivity

    var canGoBack by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    var autoOpenMangaMode by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val canConvertToReader = remember(BBSPageState.currentUrl, BBSPageState.pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(BBSPageState.currentUrl, BBSPageState.pageTitle)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                webView.onResume()
                webView.evaluateJavascript(PageJsScripts.RELOAD_BROKEN_IMAGES_JS, null)
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
    nativeMangaApi.onGoBack = {
        activity?.onBackPressedDispatcher?.onBackPressed()
    }
    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val cleanHtml = try {
                    JSON.parse(htmlResult) as? String ?: ""
                } catch (_: Exception) {
                    htmlResult?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\"") ?: ""
                }

                val urls = urlsJoined
                    .split("|||")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()

                val safeClickedIndex = clickedIndex.coerceIn(0, maxOf(0, urls.size - 1))

                MangaImagePipeline.handoffPrefetch(
                    context = context.applicationContext,
                    urls = urls,
                    clickedIndex = safeClickedIndex
                )

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempMangaIndex = safeClickedIndex
                    GlobalData.tempHtml = cleanHtml
                    GlobalData.tempTitle = title

                    webView.evaluateJavascript(PageJsScripts.FREEZE_BROKEN_IMAGES_JS, null)
                    webView.evaluateJavascript("window.stop();", null)
                    webView.stopLoading()
                    webView.onPause()

                    autoOpenMangaMode = false
                    val passUrl = BBSPageState.currentUrl ?: indexUrl
                    val encodedUrl = URLEncoder.encode(passUrl, "utf-8")
                    navController.navigate("NativeMangaPage?url=$encodedUrl")
                }
            }
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

    LaunchedEffect(BBSPageState.isLoading) {
        if (!BBSPageState.isLoading && autoOpenMangaMode) {
            webView.evaluateJavascript(PageJsScripts.AUTO_OPEN_MANGA_JS, null)

            delay(6000)
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
                webView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
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

    startLoading = { url: String ->
        BBSPageState.isLoading = true
        BBSPageState.isErrorState = false
        BBSPageState.showLoadError = false
        retryCount = 0
        CookieManager.getInstance().setCookie(url, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(10000)
            if (BBSPageState.isLoading) {
                webView.stopLoading()
                BBSPageState.isErrorState = true
                BBSPageState.isLoading = false
                BBSPageState.showLoadError = true
                isPullRefreshing = false
            }
        }
        webView.loadUrl(url)
    }
    val isNetworkAvailable by remember {
        NetworkMonitor.observeNetwork(context)
    }.collectAsState(initial = false)

    LaunchedEffect(isNetworkAvailable, BBSPageState.isErrorState) {
        if (isNetworkAvailable && BBSPageState.isErrorState) {
            val curl = webView.url
            if (!curl.isNullOrEmpty() && curl != "about:blank") {
                startLoading(curl)
            } else {
                startLoading(mobileIndexUrl)
            }
        }
    }
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            webView.evaluateJavascript(PageJsScripts.CLEANUP_FULLSCREEN_JS, null)
        } else {
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
            }
            BBSPageState.currentUrl?.let { url ->
                if (url.contains("mod=viewthread") && url.contains("tid=")) {
                    webView.evaluateJavascript(PageJsScripts.CHECK_SECTION_JS) { result ->
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
                            val pageTitle = webView.title ?: ""
                            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
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
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(isSelected, webView) {
        if (isSelected && !BBSPageState.hasSuccessfullyLoaded && !BBSPageState.isLoading && webView.url.isNullOrEmpty()) {
            startLoading(mobileIndexUrl)
        }
    }
    LaunchedEffect(Unit) {
        bottomNavBarVM.refreshEvent.collect { route ->
            isPullRefreshing = true
            if (route == "BBSPage") {
                val curl = webView.url
                if (!curl.isNullOrEmpty() && curl != "about:blank") {
                    BBSPageState.isLoading = true
                    BBSPageState.showLoadError = false
                    retryCount = 0

                    timeoutJob?.cancel()
                    timeoutJob = scope.launch {
                        delay(10000)
                        if (BBSPageState.isLoading) {
                            webView.stopLoading()
                            BBSPageState.isErrorState = true
                            BBSPageState.isLoading = false
                            BBSPageState.showLoadError = true
                            isPullRefreshing = false
                        }
                    }
                    webView.reload()
                } else {
                    startLoading(mobileIndexUrl)
                }
            }
        }
    }

    DisposableEffect(webView, isSelected) {
        val client = webView.webViewClient as? BBSGlobalWebViewClient

        if (isSelected) {
            BBSPageState.cancelPause()
            canGoBack = webView.canGoBack()
            webView.onResume()
            webView.resumeTimers()
            client?.forceInjectMangaJs(webView)
        } else {
            timeoutJob?.cancel()
            retryCount = 0
            isPullRefreshing = false
        }
        onDispose { }
    }

    LaunchedEffect(webView, isSelected) {
        if (!isSelected) return@LaunchedEffect

        snapshotFlow { webView.url }
            .collectLatest { url ->
                val safeUrl = url ?: ""
                val isHomepage = safeUrl == BBSGlobalWebViewClient.INDEX_URL ||
                        safeUrl == BBSGlobalWebViewClient.MOBILE_INDEX_URL ||
                        safeUrl == BBSGlobalWebViewClient.BBS_URL ||
                        safeUrl == BBSGlobalWebViewClient.BASE_BBS_URL ||
                        (safeUrl.startsWith("https://bbs.yamibo.com/forum.php") && !safeUrl.contains(
                            "mod="
                        ))

                if (isHomepage) {
                    for (i in 0 until 4) {
                        val cookieManager = CookieManager.getInstance()
                        val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""
                        val currentLoginState = isLoggedIn(currentCookie)

                        if (BBSPageState.isLoading || BBSPageState.showLoadError) {
                            BBSPageState.lastLoginState = currentLoginState
                        } else if (BBSPageState.lastLoginState != null && BBSPageState.lastLoginState != currentLoginState) {
                            BBSPageState.lastLoginState = currentLoginState
                            startLoading(BBSGlobalWebViewClient.MOBILE_INDEX_URL)
                            break
                        } else {
                            BBSPageState.lastLoginState = currentLoginState
                        }
                        delay(800)
                    }
                }
            }
    }

    BackHandler(enabled = true) {
        val isHomepage =
            BBSPageState.currentUrl == indexUrl || BBSPageState.currentUrl == mobileIndexUrl ||
                    BBSPageState.currentUrl == bbsUrl || BBSPageState.currentUrl == baseBbsUrl ||
                    (BBSPageState.currentUrl?.startsWith("https://bbs.yamibo.com/forum.php") == true && BBSPageState.currentUrl?.contains(
                        "mod="
                    ) == false)

        when {
            isHomepage -> {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    activity?.finish()
                }
            }

            canGoBack -> {
                webView.goBack()
            }

            else -> {
                startLoading(mobileIndexUrl)
            }
        }
    }

    val density = LocalDensity.current.density
    val initialInsets = remember(context, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            Pair(insets.top / density, insets.bottom / density)
        } else {
            Pair(24f, 0f)
        }
    }

    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var lockedStatusHeight by rememberSaveable { mutableFloatStateOf(initialInsets.first) }
    var lockedNavHeight by rememberSaveable { mutableFloatStateOf(initialInsets.second) }

    SideEffect {
        if (statusBarsPadding.value > lockedStatusHeight) {
            lockedStatusHeight = statusBarsPadding.value
        }
        if (navBarsPadding.value > lockedNavHeight) {
            lockedNavHeight = navBarsPadding.value
        }
    }

    val finalStatusHeight = lockedStatusHeight.dp
    val finalNavHeight = lockedNavHeight.dp

    val isFullscreen = isFullscreenState.value || autoOpenMangaMode
    val topSpacerColor = if (isFullscreen) Color.Black else YamiboColors.primary
    val bottomPad = if (isFullscreen) finalNavHeight else (finalNavHeight + 50.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(finalStatusHeight)
                .background(topSpacerColor)
                .align(Alignment.TopCenter)
                .zIndex(1f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = finalStatusHeight,
                    bottom = bottomPad
                )
        ) {
            AndroidView(
                modifier = Modifier,
                factory = { context ->
                    FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        (webView.parent as? ViewGroup)?.removeView(webView)

                        addView(
                            webView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )

                        webView.addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
                        webView.addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
                    }
                },
                update = { _ ->
                    canGoBack = webView.canGoBack()
                    BBSPageState.currentUrl = webView.url
                    BBSPageState.pageTitle = webView.title ?: ""
                },
                onRelease = {
                    val delayMs = if (!BBSPageState.hasExecutedInitialDelay) {
                        BBSPageState.hasExecutedInitialDelay = true
                        8000L
                    } else {
                        3000L
                    }
                    BBSPageState.schedulePause(webView, delayMs)
                }
            )

            if (BBSPageState.showLoadError) {
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
                        val curl = webView.url
                        if (!curl.isNullOrEmpty() && curl != "about:blank") {
                            startLoading(curl)
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

            if (BBSPageState.isLoading && !isPullRefreshing && !BBSPageState.hasSuccessfullyLoaded) {
                BbsSkeletonScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                )
            }

            ReaderModeFAB(
                visible = canConvertToReader && !BBSPageState.isLoading && !BBSPageState.showLoadError && !isFullscreenState.value,
                onClick = {
                    BBSPageState.currentUrl?.let { url ->
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