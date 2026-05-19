package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
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
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.module.CoilWebViewProxy
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.ui.component.UpdateDialog
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.MinePageVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.AccountSyncManager
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ImageSaveUtil
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.UpdateInfo
import org.shirakawatyu.yamibo.novel.util.UpdateManager
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import org.shirakawatyu.yamibo.novel.util.darkModeColor
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.util.history.HistoryUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.reader.ReaderModeDetector
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

private var mineWebViewPauseRunnable: Runnable? = null
private val mineWebViewHandler = Handler(Looper.getMainLooper())

class FullscreenApiMine {
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

class AboutMineJSInterface {
    var onShowAbout: (() -> Unit)? = null

    @JavascriptInterface
    fun showAbout() {
        Handler(Looper.getMainLooper()).post { onShowAbout?.invoke() }
    }
}

class HistoryJSInterface {
    var onShowHistory: (() -> Unit)? = null

    @JavascriptInterface
    fun showHistory() {
        Handler(Looper.getMainLooper()).post { onShowHistory?.invoke() }
    }
}

private var cachedFullscreenApiMine: FullscreenApiMine? = null
private var cachedNativeMangaApiMine: NativeMangaMineJSInterface? = null
private var cachedAboutApiMine: AboutMineJSInterface? = null
private var cachedHistoryApiMine: HistoryJSInterface? = null

private fun decodeHistoryTargetUrl(initUrl: String): String {
    val decodedUrl = runCatching { URLDecoder.decode(initUrl, "utf-8") }.getOrElse { initUrl }
    val absoluteUrl = if (decodedUrl.startsWith("http://") || decodedUrl.startsWith("https://")) {
        decodedUrl
    } else {
        "https://bbs.yamibo.com/${decodedUrl.removePrefix("/")}"
    }
    return normalizeHistoryComparableUrl(absoluteUrl)
}

private fun normalizeHistoryComparableUrl(url: String?): String {
    return url
        ?.trim()
        ?.substringBefore("#")
        ?.removeSuffix("/")
        .orEmpty()
}

private fun isSameHistoryTargetUrl(actualUrl: String?, targetUrl: String?): Boolean {
    val actual = normalizeHistoryComparableUrl(actualUrl)
    val target = normalizeHistoryComparableUrl(targetUrl)
    if (actual.isBlank() || target.isBlank()) return false
    if (actual == target) return true

    val actualTid = org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner.extractTidFromUrl(actual)
    val targetTid = org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner.extractTidFromUrl(target)
    return actualTid != null && actualTid == targetTid
}

private sealed interface MineDialogState {
    object None : MineDialogState
    object About : MineDialogState
    object CheckingUpdate : MineDialogState
    object Latest : MineDialogState
    data class Update(val info: UpdateInfo) : MineDialogState
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MinePage(
    isSelected: Boolean,
    navController: NavController,
    webChromeClient: WebChromeClient,
    initUrl: String = "",
    fromHistory: Boolean = false
) {
    val mineUrl = "https://bbs.yamibo.com/home.php?mod=space&do=profile&mycenter=1&mobile=2"
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val activityMinePageVM: MinePageVM = viewModel(viewModelStoreOwner = context as ComponentActivity)
    val routeMinePageVM: MinePageVM = viewModel()
    val minePageVM = if (fromHistory) routeMinePageVM else activityMinePageVM
    val historyTargetUrl = remember(fromHistory, initUrl) {
        if (fromHistory && initUrl.isNotBlank()) decodeHistoryTargetUrl(initUrl) else null
    }

    var activeHistoryTargetUrl by remember { mutableStateOf<String?>(historyTargetUrl) }
    var activeLoadTargetUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember {
        mutableStateOf(run {
            val cachedView = minePageVM.cachedWebView
            val cachedUrl = cachedView?.url
            if (fromHistory && historyTargetUrl != null) {
                // 历史帖子页使用 route-scoped MinePageVM 的独立 WebView，
                // 不再读取真正 MinePage 的 cachedWebView 状态。
                true
            } else if (!fromHistory) {
                (cachedUrl == null || cachedView.tag?.toString()?.startsWith("recycled") == true || cachedUrl == "about:blank")
            } else {
                true
            }
        })
    }
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
    var mineDialog by remember { mutableStateOf<MineDialogState>(MineDialogState.None) }

    val canConvertToReader = remember(currentUrl, pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl, pageTitle)
    }
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )
    lateinit var startLoading: (webView: WebView, url: String) -> Unit

    fun isMineRootUrl(url: String?): Boolean {
        val normalized = normalizeHistoryComparableUrl(url)
        return normalized == normalizeHistoryComparableUrl(mineUrl) || normalized.contains("mycenter=1") || normalized.contains("mod=logging")
    }

    fun isSamePageTargetUrl(actualUrl: String?, targetUrl: String?): Boolean {
        val target = normalizeHistoryComparableUrl(targetUrl)
        if (target.isBlank()) return true
        if (target == normalizeHistoryComparableUrl(mineUrl) || target.contains("mycenter=1")) {
            return isMineRootUrl(actualUrl)
        }
        return isSameHistoryTargetUrl(actualUrl, target)
    }

    fun currentExpectedLoadTarget(): String? {
        return activeLoadTargetUrl ?: activeHistoryTargetUrl
    }

    fun isExpectedLoadCallback(url: String?): Boolean {
        val target = currentExpectedLoadTarget()
        return target.isNullOrBlank() || isSamePageTargetUrl(url, target)
    }

    fun finishLoadingIfExpected(view: WebView?, url: String?) {
        if (hasError || view == null || !isLoading || !isExpectedLoadCallback(url)) return
        timeoutJob?.cancel()
        retryCount = 0
        isLoading = false
        isPullRefreshing = false
        showLoadError = false
        activeLoadTargetUrl = null
        if (fromHistory) activeHistoryTargetUrl = null
    }

    fun markMainFrameErrorIfExpected(url: String?) {
        if (!isExpectedLoadCallback(url)) return
        timeoutJob?.cancel()
        retryCount = 0
        hasError = true
        isLoading = false
        isPullRefreshing = false
        activeLoadTargetUrl = null
        activeHistoryTargetUrl = null
        showLoadError = true
    }

    fun evaluateCanGoBack(view: WebView?): Boolean {
        if (view == null || !view.canGoBack()) return false
        val currUrl = view.url ?: ""
        // 只将真正的"我的主页" (含mycenter=1) 或 登录页 当做根页面，其它用户的个人资料页可以GoBack
        if (currUrl == mineUrl || currUrl.contains("mycenter=1") || currUrl.contains("mod=logging")) return false

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
        val targetForThisLoad = normalizeHistoryComparableUrl(url)
        activeLoadTargetUrl = targetForThisLoad
        if (fromHistory) activeHistoryTargetUrl = targetForThisLoad
        isLoading = true
        hasError = false
        showLoadError = false
        retryCount = 0
        webView.stopLoading()
        CookieManager.getInstance().setCookie(url, GlobalData.currentCookie)
        CookieManager.getInstance().flush()
        runTimeout(webView) {
            if (activeLoadTargetUrl != targetForThisLoad) return@runTimeout
            Log.w("MinePage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                if (activeLoadTargetUrl != targetForThisLoad) return@runTimeout
                Log.e("MinePage", "Retry timed out. Giving up.")
                hasError = true
                isLoading = false
                isPullRefreshing = false
                activeLoadTargetUrl = null
                activeHistoryTargetUrl = null
                showLoadError = true
                webView.stopLoading()
            }
            webView.loadUrl(url)
        }
        webView.loadUrl(url)
    }

    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context)
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

    val fullscreenApi = remember(fromHistory) {
        if (fromHistory) {
            FullscreenApiMine()
        } else {
            if (cachedFullscreenApiMine == null) {
                cachedFullscreenApiMine = FullscreenApiMine()
            }
            cachedFullscreenApiMine!!
        }
    }
    fullscreenApi.onStateChange = { isFullscreen -> isFullscreenState.value = isFullscreen }
    fullscreenApi.onMangaActionDone = { autoOpenMangaMode = false }
    fullscreenApi.onSaveImage = { url ->
        android.app.AlertDialog.Builder(context)
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

    val nativeMangaApi = remember(fromHistory) {
        if (fromHistory) {
            NativeMangaMineJSInterface()
        } else {
            if (cachedNativeMangaApiMine == null) {
                cachedNativeMangaApiMine = NativeMangaMineJSInterface()
            }
            cachedNativeMangaApiMine!!
        }
    }
    val aboutApi = remember(fromHistory) {
        if (fromHistory) {
            AboutMineJSInterface()
        } else {
            if (cachedAboutApiMine == null) {
                cachedAboutApiMine = AboutMineJSInterface()
            }
            cachedAboutApiMine!!
        }
    }
    aboutApi.onShowAbout = { mineDialog = MineDialogState.About }
    val historyApi = remember(fromHistory) {
        if (fromHistory) {
            HistoryJSInterface()
        } else {
            if (cachedHistoryApiMine == null) {
                cachedHistoryApiMine = HistoryJSInterface()
            }
            cachedHistoryApiMine!!
        }
    }
    historyApi.onShowHistory = { navController.navigate("HistoryPage") }
    var pendingSearchUrl by remember { mutableStateOf<String?>(null) }
    val searchNavApi = remember {
        object {
            @JavascriptInterface
            fun navigateToPost(url: String) {
                Handler(Looper.getMainLooper()).post {
                    pendingSearchUrl = url
                }
            }
        }
    }

    val mineWebView = remember(fromHistory, historyTargetUrl) {
        val isNew = minePageVM.cachedWebView == null
        val webView = minePageVM.getOrAcquireWebView(context)

        if (isNew) {
            webView.settings.apply {
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
        }

        // 历史帖子详情页使用独立的 route-scoped WebView，但 composable 可能在进入 NativeMangaPage / ReaderPage 后重建。
        // 因此 fromHistory 时每次都重新绑定 JS interface，避免 WebView 内还拿着上一次组合的回调对象。
        if (isNew || fromHistory) {
            if (fromHistory && !isNew) {
                try {
                    webView.removeJavascriptInterface("AndroidFullscreen")
                    webView.removeJavascriptInterface("NativeMangaApi")
                    webView.removeJavascriptInterface("AboutApi")
                    webView.removeJavascriptInterface("HistoryApi")
                    webView.removeJavascriptInterface("AndroidSearchNav")
                } catch (_: Exception) {
                }
            }
            webView.addJavascriptInterface(fullscreenApi, "AndroidFullscreen")
            webView.addJavascriptInterface(nativeMangaApi, "NativeMangaApi")
            webView.addJavascriptInterface(aboutApi, "AboutApi")
            webView.addJavascriptInterface(historyApi, "HistoryApi")
            webView.addJavascriptInterface(searchNavApi, "AndroidSearchNav")
        }
        webView.webChromeClient = webChromeClient

        webView
    }

    val rootHistoryIndex = remember { mineWebView.copyBackForwardList().currentIndex }

    fun resumeMineWebViewAfterChildPage() {
        mineWebViewPauseRunnable?.let {
            mineWebViewHandler.removeCallbacks(it)
            mineWebViewPauseRunnable = null
        }
        try {
            mineWebView.onResume()
            mineWebView.resumeTimers()
            // 从 NativeMangaPage / ReaderPage 返回时，不重新加载帖子，只恢复 WebView 的定时器与点击脚本。
            mineWebView.evaluateJavascript(PageJsScripts.RELOAD_BROKEN_IMAGES_JS, null)
            mineWebView.evaluateJavascript(PageJsScripts.PJAX_FALLBACK_JS, null)
            mineWebView.evaluateJavascript(PageJsScripts.THREAD_LIST_CLICK_FIX_JS, null)
            mineWebView.evaluateJavascript(PageJsScripts.MINE_INJECT_PSWP_AND_MANGA_JS, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(mineWebView, isSelected, initUrl) {
        if (fromHistory && historyTargetUrl != null) {
            activeHistoryTargetUrl = historyTargetUrl

            val currentWebViewUrl = mineWebView.url ?: ""
            if (isSameHistoryTargetUrl(currentWebViewUrl, historyTargetUrl)) {
                activeLoadTargetUrl = null
                activeHistoryTargetUrl = null
                isLoading = false
                canGoBack = evaluateCanGoBack(mineWebView)
            } else {
                startLoading(mineWebView, historyTargetUrl)
            }
        } else if (!fromHistory) {
            val currentWebViewUrl = mineWebView.url
            if (isSelected && (currentWebViewUrl == null || mineWebView.tag?.toString()
                    ?.startsWith("recycled") == true || currentWebViewUrl == "about:blank")
            ) {
                mineWebView.tag = null
                if (savedMangaUrl != null) {
                    val restoreUrl = savedMangaUrl
                    if (isSamePageTargetUrl(currentWebViewUrl, restoreUrl)) {
                        // 从 NativeMangaPage 返回原帖时，WebView 仍在同一帖子；不要重新 load，避免回退体验倒退成加载圈。
                        savedMangaUrl = null
                        needFallbackToHome = false
                        activeLoadTargetUrl = null
                        isLoading = false
                        canGoBack = evaluateCanGoBack(mineWebView)
                        resumeMineWebViewAfterChildPage()
                    } else {
                        startLoading(mineWebView, restoreUrl!!)
                        savedMangaUrl = null
                        needFallbackToHome = true
                    }
                } else {
                    startLoading(mineWebView, mineUrl)
                }
            } else {
                isLoading = false
                canGoBack = evaluateCanGoBack(mineWebView)
            }
        }
    }

    DisposableEffect(mineWebView) {
        onDispose {
            timeoutJob?.cancel()
            activeLoadTargetUrl = null
            activeHistoryTargetUrl = null

            if (fromHistory) {
                val nextRoute = navController.currentDestination?.route.orEmpty()
                val keepHistoryWebViewForChildPage =
                    nextRoute.startsWith("NativeMangaPage") || nextRoute.startsWith("ReaderPage")

                if (!keepHistoryWebViewForChildPage) {
                    // 真正离开历史帖子详情页时，清掉这个 route-scoped WebView。
                    // 它不会写回真正的 MinePageVM，因此返回 MinePage 时不会再显示历史帖子。
                    try {
                        mineWebView.stopLoading()
                        mineWebView.clearHistory()
                    } catch (e: Exception) {
                        Log.w("MinePage", "Cleanup history WebView failed", e)
                    }
                    minePageVM.scheduleRelease(delayMs = 0L)
                } else {
                    // 去原生漫画 / 阅读器时保留几分钟，返回详情页可以恢复原 WebView。
                    minePageVM.scheduleRelease()
                }
            } else {
                minePageVM.scheduleRelease()
            }
        }
    }

    nativeMangaApi.onGoBack = {
        if (fromHistory) {
            val currentIndex = mineWebView.copyBackForwardList().currentIndex
            if (currentIndex > rootHistoryIndex + 1 && mineWebView.canGoBack()) {
                mineWebView.goBack()
            } else {
                navController.popBackStack()
            }
        } else {
            if (evaluateCanGoBack(mineWebView)) {
                mineWebView.goBack()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
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

    LaunchedEffect(Unit) {
        bottomNavBarVM.goHomeEvent.collect { route ->
            if (route == "MinePage") {
                startLoading(mineWebView, mineUrl)
            }
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.darkModeEvent.collect { _ ->
            val enable = GlobalData.isDarkMode.value
            val js = PageJsScripts.getDarkModeSetJs(enable, GlobalData.darkModeTheme.value)
            mineWebView.evaluateJavascript(js, null)
        }
    }

    LaunchedEffect(pendingSearchUrl) {
        val url = pendingSearchUrl ?: return@LaunchedEffect
        startLoading(mineWebView, url)
        pendingSearchUrl = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (autoOpenMangaMode) {
                    autoOpenMangaMode = false
                }

                resumeMineWebViewAfterChildPage()
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

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempMangaIndex = safeClickedIndex
                    GlobalData.tempHtml = cleanHtml
                    GlobalData.tempTitle = title

                    // 老版本返回原帖很快，是因为没有把帖子 WebView 冻住后再恢复。
                    // 这里不要 window.stop()/stopLoading()/onPause()，否则从 NativeMangaPage 返回时
                    // route-scoped 历史 WebView 容易停在半冻结状态，表现为 JS / 点击失效。
                    resumeMineWebViewAfterChildPage()

                    autoOpenMangaMode = false
                    val passUrl = currentUrl ?: mineWebView.url ?: historyTargetUrl ?: "https://bbs.yamibo.com/forum.php"

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
            GlobalData.webProgress.value = 100
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
            private fun getToggleHeaderJs(isMineRoot: Boolean): String {
                return """
                javascript:(function() {
                    var style = document.getElementById('mine-dynamic-header');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'mine-dynamic-header';
                        document.head.appendChild(style);
                    }
                    if ($isMineRoot) {
                        style.innerHTML = '.header .z, .header .y { display: none !important; }';
                        var aboutBtn = document.getElementById('mine-about-btn');
                        if (!aboutBtn) {
                            aboutBtn = document.createElement('a');
                            aboutBtn.id = 'mine-about-btn';
                            aboutBtn.innerText = '关于';
                            aboutBtn.href = 'javascript:void(0)';
                            aboutBtn.style.cssText = 'position:fixed; right:12px; top:8px; height:32px; line-height:32px; padding:0 14px; color:#ffffff; background:rgba(0,0,0,0.35); border-radius:16px; text-decoration:none; z-index:9999; font-size:15px; font-weight:500;';
                            aboutBtn.addEventListener('click', function(e) {
                                e.preventDefault();
                                window.AboutApi.showAbout();
                            });
                            document.body.appendChild(aboutBtn);
                        } else {
                            aboutBtn.style.display = '';
                        }
                        var historyBtn = document.getElementById('mine-history-btn');
                        if (!historyBtn) {
                            historyBtn = document.createElement('a');
                            historyBtn.id = 'mine-history-btn';
                            historyBtn.innerText = '历史';
                            historyBtn.href = 'javascript:void(0)';
                            historyBtn.style.cssText = 'position:fixed; left:12px; top:8px; height:32px; line-height:32px; padding:0 14px; color:#ffffff; background:rgba(0,0,0,0.35); border-radius:16px; text-decoration:none; z-index:9999; font-size:15px; font-weight:500;';
                            historyBtn.addEventListener('click', function(e) {
                                e.preventDefault();
                                if(window.HistoryApi) window.HistoryApi.showHistory();
                            });
                            document.body.appendChild(historyBtn);
                        } else {
                            historyBtn.style.display = '';
                        }
                    } else {
                        style.innerHTML = '.header, .header .z, .header .y { display: block !important; visibility: visible !important; opacity: 1 !important; }';
                        var btnA = document.getElementById('mine-about-btn');
                        if (btnA) btnA.style.display = 'none';
                        var btnH = document.getElementById('mine-history-btn');
                        if (btnH) btnH.style.display = 'none';
                    }
                })();
            """.trimIndent()
            }
            override fun onFormResubmission(
                view: WebView?,
                dontResend: android.os.Message?,
                resend: android.os.Message?
            ) {
                resend?.sendToTarget()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val urlStr = request?.url?.toString() ?: ""

                if (urlStr.isNotEmpty() && !urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)))
                    } catch (_: Exception) {
                    }
                    return true
                }

                if (!fromHistory && isSelected && isHomepageUrl(urlStr) && view != null) {
                    scope.launch(Dispatchers.IO) {
                        delay(500L)
                        AccountSyncManager.syncCookieAndCheckSign(
                            context,
                            "LOGIN_REDIRECT_INTERCEPT"
                        )
                    }
                    startLoading(view, mineUrl)
                    return true
                }

                return super.shouldOverrideUrlLoading(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val safeUrl = url ?: ""
                if (safeUrl.isNotEmpty() && !safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true || url?.startsWith(
                        "data:"
                    ) == true
                ) return

                if (!isExpectedLoadCallback(url)) {
                    super.onPageStarted(view, url, favicon)
                    return
                }

                val checkUrl = url ?: ""

                // 区分自己的主页(含mycenter=1) 或 登录页 才算是 Root
                val isHomePage =
                    isHomepageUrl(checkUrl) || checkUrl == mineUrl || checkUrl.contains("mycenter=1") || checkUrl.contains("mod=logging")
                bottomNavBarVM.isMineAtRoot = isHomePage

                if (!fromHistory && isSelected && isHomepageUrl(checkUrl) && view != null) {
                    view.stopLoading()
                    scope.launch(Dispatchers.IO) {
                        delay(500L)
                        AccountSyncManager.syncCookieAndCheckSign(
                            context,
                            "LOGIN_REDIRECT_INTERCEPT"
                        )
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

                // 夜间模式：拦截主框架 HTML，在渲染前注入暗色 CSS，消除白闪
                if (request?.isForMainFrame == true &&
                    request.method == "GET" &&
                    GlobalData.isDarkMode.value &&
                    urlStr.contains("bbs.yamibo.com")
                ) {
                    val html = YamiboRetrofit.proxyHtmlForDarkMode(request)
                    if (html != null) {
                        val modified = PageJsScripts.injectDarkModeCssIntoHtml(html, GlobalData.darkModeTheme.value)
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
                                CoilWebViewProxy.interceptImage(context, urlStr, headers)
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
                    } else {
                        val proxyResponse = YamiboRetrofit.proxyWebViewResource(request)
                        if (proxyResponse != null) return proxyResponse
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // 只有进入自己的主页或登录页时才清空历史，别人的主页不要清空
                if (url != null && (url == mineUrl || url.contains("mycenter=1") || url.contains("mod=logging"))) {
                    view?.clearHistory()
                }
                canGoBack = evaluateCanGoBack(view)

                val checkUrl = url ?: ""
                val isHomePage =
                    isHomepageUrl(checkUrl) || checkUrl == mineUrl || checkUrl.contains("mycenter=1") || checkUrl.contains("mod=logging")
                bottomNavBarVM.isMineAtRoot = isHomePage
                if (!isLoading) {
                    GlobalData.webProgress.value = 100
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url == "about:blank" || url?.contains("warmup=true") == true) return
                super.onPageCommitVisible(view, url)

                if (!isExpectedLoadCallback(url)) return

                pageTitle = view?.title ?: ""
                finishLoadingIfExpected(view, url)

                // 使用外部提取的特定于 MinePage 的脚本常量
                view?.evaluateJavascript(PageJsScripts.MINE_INJECT_PSWP_AND_MANGA_JS, null)
                view?.evaluateJavascript(PageJsScripts.PJAX_FALLBACK_JS, null)
                view?.evaluateJavascript(PageJsScripts.THREAD_LIST_CLICK_FIX_JS, null)
                view?.evaluateJavascript(PageJsScripts.SEARCH_DIRECT_NAV_JS, null)

                if (GlobalData.isDarkMode.value) {
                    view?.evaluateJavascript(
                        PageJsScripts.getDarkModeSetJs(true, GlobalData.darkModeTheme.value), null
                    )
                }

                // Explicitly rule out mod=logging for the header injection
                val isMineRoot = url != null && (url == mineUrl || url.contains("mycenter=1")) && !url.contains("mod=logging")
                val toggleHeaderJs = getToggleHeaderJs(isMineRoot)
                view?.evaluateJavascript(toggleHeaderJs, null)

                // 记录浏览历史
                if (url != null && HistoryUtil.isThreadUrl(url)) {
                    view?.evaluateJavascript(PageJsScripts.EXTRACT_THREAD_INFO_JS) { jsonStr ->
                        try {
                            val cleanJson = if (jsonStr?.startsWith("\"") == true) JSON.parse(jsonStr) as String else jsonStr
                            val obj = JSON.parseObject(cleanJson)
                            val title = obj.getString("title") ?: ""
                            val author = obj.getString("author") ?: ""
                            val section = obj.getString("section") ?: ""
                            if (title.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    HistoryUtil.addOrUpdateHistory(url, title, author, section)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isExpectedLoadCallback(url)) {
                    super.onPageFinished(view, url)
                    return
                }

                finishLoadingIfExpected(view, url)

                super.onPageFinished(view, url)
                currentUrl = url

                // 只有进入自己的主页或登录页时才清空历史
                if (url != null && (url == mineUrl || url.contains("mycenter=1") || url.contains("mod=logging"))) {
                    view?.clearHistory()
                }

                canGoBack = evaluateCanGoBack(view)
                val isMineRoot = url != null && (url == mineUrl || url.contains("mycenter=1")) && !url.contains("mod=logging")
                val toggleHeaderJs = getToggleHeaderJs(isMineRoot)
                view?.evaluateJavascript(toggleHeaderJs, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    markMainFrameErrorIfExpected(request.url?.toString())
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
                if (failingUrl != null && failingUrl == view?.url) {
                    markMainFrameErrorIfExpected(failingUrl)
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
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    markMainFrameErrorIfExpected(request.url?.toString())
                }
            }
        }

    }

    DisposableEffect(mineWebView, isSelected) {
        if (isSelected) {
            resumeMineWebViewAfterChildPage()
        } else {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
            isPullRefreshing = false
        }
        onDispose { }
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
        val isAtMineHome = checkUrl == mineUrl || checkUrl.contains("mycenter=1") || checkUrl.contains("mod=logging")
        when {
            fromHistory -> {
                val currentIndex = mineWebView.copyBackForwardList().currentIndex
                if (currentIndex > rootHistoryIndex + 1 && mineWebView.canGoBack()) {
                    timeoutJob?.cancel()
                    mineWebView.goBack()
                } else {
                    timeoutJob?.cancel()
                    navController.popBackStack()
                }
            }

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
    val topSpacerColor = if (isFullscreen) Color.Black else darkThemeColor(YamiboColors.primary) { statusBar }
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
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        (mineWebView.parent as? ViewGroup)?.removeView(mineWebView)
                        addView(
                            mineWebView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                update = { _ ->
                    canGoBack = evaluateCanGoBack(mineWebView)
                    currentUrl = mineWebView.url
                    pageTitle = mineWebView.title ?: ""
                },
                onRelease = { _ ->
                    timeoutJob?.cancel()

                    val nextRoute = navController.currentDestination?.route.orEmpty()
                    val keepAliveForChildPage =
                        nextRoute.startsWith("NativeMangaPage") || nextRoute.startsWith("ReaderPage")

                    mineWebViewPauseRunnable?.let {
                        mineWebViewHandler.removeCallbacks(it)
                        mineWebViewPauseRunnable = null
                    }

                    if (!keepAliveForChildPage) {
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

            val expectedLoadTarget = currentExpectedLoadTarget()
            val shouldBlockOldWebContent = isLoading && !isPullRefreshing && (
                    fromHistory || !isSamePageTargetUrl(mineWebView.url, expectedLoadTarget)
                    )

            // 只要当前 WebView 还不是本次目标页面，就用不透明遮罩挡住旧 DOM。
            // 这样从历史帖子返回 MinePage 时，不会在个人主页提交前露出刚才的帖子。
            AnimatedVisibility(
                visible = shouldBlockOldWebContent,
                enter = EnterTransition.None,
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) {
                            detectTapGestures { }
                            detectVerticalDragGestures { _, _ -> }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            if (isLoading && !isPullRefreshing && !shouldBlockOldWebContent) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = darkModeColor(YamiboColors.secondary, YamiboColors.secondaryDark)
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

            // 关于 / 检查更新 / 更新弹窗：只保留一个状态源，避免多个 AlertDialog 抢状态。
            when (val dialog = mineDialog) {
                MineDialogState.None -> Unit

                MineDialogState.About,
                MineDialogState.CheckingUpdate -> {
                    val isChecking = dialog == MineDialogState.CheckingUpdate
                    val pkgInfo = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }.getOrNull()
                    }
                    val currentVersion = pkgInfo?.versionName ?: ""
                    val repoUrl = "https://github.com/prprbell/YamiboReaderPro"
                    val uriHandler = LocalUriHandler.current

                    AlertDialog(
                        onDismissRequest = {
                            if (!isChecking) mineDialog = MineDialogState.None
                        },
                        title = { Text("关于", fontSize = 18.sp) },
                        text = {
                            Column {
                                Text(
                                    "YamiboReaderPro是百合会论坛的非官方阅读器，支持小说和漫画阅读。",
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("当前版本号：$currentVersion", fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))

                                Row {
                                    Text("仓库: ", fontSize = 13.sp)
                                    ClickableText(
                                        text = buildAnnotatedString {
                                            withStyle(
                                                SpanStyle(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ) {
                                                append(repoUrl)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                        onClick = { uriHandler.openUri(repoUrl) }
                                    )
                                }

                                if (isChecking) {
                                    Spacer(Modifier.height(16.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "正在检查...",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = !isChecking,
                                onClick = {
                                    if (isChecking) return@Button

                                    mineDialog = MineDialogState.CheckingUpdate
                                    scope.launch(Dispatchers.IO) {
                                        val info = UpdateManager.checkForUpdate()
                                        withContext(Dispatchers.Main) {
                                            mineDialog = if (info != null) {
                                                MineDialogState.Update(info)
                                            } else {
                                                MineDialogState.Latest
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(if (isChecking) "检查中..." else "检查更新")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isChecking,
                                onClick = { mineDialog = MineDialogState.None }
                            ) {
                                Text("关闭")
                            }
                        }
                    )
                }

                MineDialogState.Latest -> {
                    AlertDialog(
                        onDismissRequest = { mineDialog = MineDialogState.None },
                        title = { Text("检查更新") },
                        text = {
                            Text(
                                "已是最新版本",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { mineDialog = MineDialogState.None }) {
                                Text("关闭")
                            }
                        }
                    )
                }

                is MineDialogState.Update -> {
                    UpdateDialog(
                        info = dialog.info,
                        onDismiss = { mineDialog = MineDialogState.None },
                        onSkipVersion = { version ->
                            SettingsUtil.saveSkipVersion(version)
                            mineDialog = MineDialogState.None
                        }
                    )
                }
            }

        }
    }
}