package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.shirakawatyu.yamibo.novel.ui.state.BbsResumeRecoveryMode
import org.shirakawatyu.yamibo.novel.ui.state.BbsSurfaceTrust
import org.shirakawatyu.yamibo.novel.ui.state.selectBbsRecoveryUrl
import org.shirakawatyu.yamibo.novel.ui.state.BbsWebViewPauseScheduler
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.BbsSkeletonScreen
import org.shirakawatyu.yamibo.novel.ui.widget.ReaderModeFAB
import org.shirakawatyu.yamibo.novel.util.ActivityWebViewLifecycleObserver
import org.shirakawatyu.yamibo.novel.util.ImageSaveUtil
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.darkThemeColor
import org.shirakawatyu.yamibo.novel.util.history.HistoryUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.network.NetworkMonitor
import org.shirakawatyu.yamibo.novel.util.reader.ReaderModeDetector
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import org.shirakawatyu.yamibo.novel.util.StaticAssetProxy

class FullscreenApi {
    var onStateChange: ((Boolean) -> Unit)? = null
    var onMangaActionDone: (() -> Unit)? = null
    var onSaveImage: ((String) -> Unit)? = null
    var onCopyLink: ((String, String) -> Unit)? = null

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

    @JavascriptInterface
    fun copyLink(title: String, url: String) {
        Handler(Looper.getMainLooper()).post { onCopyLink?.invoke(title, url) }
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

class BBSGlobalWebViewClient(
    private val context: Context,
    private val bbsPageState: BBSPageState
) : YamiboWebViewClient() {
    private val contentImageCount = AtomicInteger(0)
    private var activeMainFrameUrl: String? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private var mainFrameTimeoutRunnable: Runnable? = null
    private var commitVisibleFallbackRunnable: Runnable? = null

    companion object {
        const val INDEX_URL = "https://bbs.yamibo.com/forum.php"
        const val MOBILE_INDEX_URL = "https://bbs.yamibo.com/forum.php?mobile=2"
        const val BBS_URL = "https://bbs.yamibo.com/index.php?mobile=2"
        const val BASE_BBS_URL = "https://bbs.yamibo.com/"
        private val IMAGE_EXT_REGEX = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)

        fun isYamiboUrl(url: String): Boolean {
            val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
            return host == "bbs.yamibo.com" || host == "m.yamibo.com" ||
                    host == "www.yamibo.com" || host == "yamibo.com"
        }
    }

    fun forceInjectMangaJs(webView: WebView) {
        webView.evaluateJavascript(PageJsScripts.BBS_MANGA_REINJECT_JS, null)
    }

    fun dispose() {
        cancelMainFrameTimeout()
        cancelCommitVisibleFallback()
        mainHandler.removeCallbacksAndMessages(null)
        activeMainFrameUrl = null
    }

    private fun isBbsHomeUrl(url: String): Boolean {
        return url == INDEX_URL ||
                url == MOBILE_INDEX_URL ||
                url == BBS_URL ||
                url == BASE_BBS_URL ||
                url == "https://bbs.yamibo.com/?mobile=2" ||
                url == "https://bbs.yamibo.com/?mobile=no"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        GlobalData.webProgress.value = 0
        contentImageCount.set(0)
        super.onPageStarted(view, url, favicon)
        val safeUrl = url ?: ""
        val isHomepage =
            isBbsHomeUrl(safeUrl) ||
                    (safeUrl.startsWith("https://bbs.yamibo.com/forum.php") && !safeUrl.contains("mod="))
        activeMainFrameUrl = safeUrl
        (context as? ComponentActivity)?.let { activity ->
            val navBarVM =
                androidx.lifecycle.ViewModelProvider(activity)[BottomNavBarVM::class.java]
            navBarVM.isBbsAtRoot = isHomepage
        }

        bbsPageState.markMainFrameLoadStarted(url)

        startMainFrameTimeout(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val safeUrl = url ?: ""
        val isHomepage =
            isBbsHomeUrl(safeUrl) ||
                    (safeUrl.startsWith("https://bbs.yamibo.com/forum.php") && !safeUrl.contains("mod="))
        (context as? ComponentActivity)?.let { activity ->
            val navBarVM =
                androidx.lifecycle.ViewModelProvider(activity)[BottomNavBarVM::class.java]
            navBarVM.isBbsAtRoot = isHomepage
        }
        if (!bbsPageState.isLoading) {
            GlobalData.webProgress.value = 100
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: ""
        if (url.isBlank()) return false

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return openExternalUrl(url)
        }

        if (!isYamiboUrl(url)) {
            openExternalUrl(url)
            return true
        }

        return super.shouldOverrideUrlLoading(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val safeUrl = url ?: ""
        if (safeUrl.isBlank()) return false

        if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
            return openExternalUrl(safeUrl)
        }

        if (!isYamiboUrl(safeUrl)) {
            openExternalUrl(safeUrl)
            return true
        }

        return super.shouldOverrideUrlLoading(view, safeUrl)
    }

    private fun openExternalUrl(url: String): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            false
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

        // 主题模式：拦截主框架 HTML，在渲染前注入主题 CSS，消除白闪
        if (request?.isForMainFrame == true &&
            request.method == "GET" &&
            (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) &&
            YamiboWebViewClient.shouldProxyHtmlForTheme(urlStr, accept)
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
                urlStr.contains(IMAGE_EXT_REGEX) ||
                urlStr.contains("attachment")

        if (request?.isForMainFrame == false &&
            request.method == "GET" &&
            urlStr.contains("yamibo.com") &&
            isImage
        ) {
            if (!urlStr.contains("smiley") &&
                !urlStr.contains("avatar") &&
                !urlStr.contains("common") &&
                !urlStr.contains("static/image") &&
                !urlStr.contains("template") &&
                !urlStr.contains("block")
            ) {
                contentImageCount.getAndIncrement()

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

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        view?.evaluateJavascript(PageJsScripts.BBS_COMMIT_BOOTSTRAP_JS, null)
        // 预装常驻页面 agent：visibilitychange/pageshow 时自修复，不依赖 Native 异步 probe
        view?.evaluateJavascript(BbsResumeHealthAgent.PAGE_VISIBILITY_AGENT_JS, null)

        if (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) {
            view?.evaluateJavascript(
                PageJsScripts.getThemeSetJs(
                    GlobalData.isDarkMode.value,
                    GlobalData.darkModeTheme.value,
                    GlobalData.lightModeTheme.value
                ), null
            )
        }

        bbsPageState.markMainFrameCommitted(url, view?.title)
        scheduleCommitVisibleFallback(view, url)

        if (url != null && HistoryUtil.isThreadUrl(url)) {
            view?.evaluateJavascript(PageJsScripts.EXTRACT_THREAD_INFO_JS) { jsonStr ->
                try {
                    val cleanJson = if (jsonStr?.startsWith("\"") == true) JSON.parse(jsonStr) as String else jsonStr
                    val obj = JSON.parseObject(cleanJson)
                    val title = obj.getString("title") ?: ""
                    val author = obj.getString("author") ?: ""
                    val section = obj.getString("section") ?: ""
                    if (title.isNotBlank()) {
                        CoroutineScope(Dispatchers.IO).launch {
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
        super.onPageFinished(view, url)
        cancelMainFrameTimeout()
        cancelCommitVisibleFallback()

        view?.let {
            forceInjectMangaJs(it)
        }

        val finishedUrl = url ?: view?.url
        val isHomepage =
            finishedUrl == INDEX_URL ||
                    finishedUrl == BBS_URL ||
                    finishedUrl == BASE_BBS_URL ||
                    finishedUrl == MOBILE_INDEX_URL
        if (isHomepage) {
            view?.clearHistory()
        }

        if (!bbsPageState.isErrorState) {
            bbsPageState.markLoadSucceeded(finishedUrl)
        }
        checkAndUpdateLoginState()
    }

    private fun checkAndUpdateLoginState() {
        val cookieManager = CookieManager.getInstance()
        val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""
        val currentLoginState = currentCookie.contains("EeqY_2132_auth=")

        bbsPageState.updateLoginState(currentLoginState)
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        handleErrorState()
        (context as? org.shirakawatyu.yamibo.novel.MainActivity)
            ?.recreateBbsWebViewAfterRendererGone(view)
        return true
    }

    private fun isSameUrlIgnoringHash(a: String?, b: String?): Boolean {
        val aa = a?.substringBefore("#")?.removeSuffix("/") ?: return false
        val bb = b?.substringBefore("#")?.removeSuffix("/") ?: return false
        return aa == bb
    }

    private fun shouldHandleMainFrameError(view: WebView?, failingUrl: String?): Boolean {
        if (!isYamiboUrl(failingUrl ?: "")) return false

        if (failingUrl.isNullOrBlank()) {
            return !bbsPageState.hasSuccessfullyLoaded
        }

        val activeUrl = activeMainFrameUrl

        if (!activeUrl.isNullOrBlank()) {
            return isSameUrlIgnoringHash(failingUrl, activeUrl)
        }

        val currentUrl = view?.url
        return currentUrl.isNullOrBlank() ||
                currentUrl == "about:blank" ||
                isSameUrlIgnoringHash(failingUrl, currentUrl)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            cancelMainFrameTimeout()
            cancelCommitVisibleFallback()
            val failingUrl = request.url?.toString()
            if (shouldHandleMainFrameError(view, failingUrl)) {
                handleErrorState()
            } else {
                ignoreMainFrameErrorButFinishLoading()
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
        super.onReceivedError(view, errorCode, description, failingUrl)
        cancelMainFrameTimeout()
        cancelCommitVisibleFallback()
        if (shouldHandleMainFrameError(view, failingUrl)) {
            handleErrorState()
        } else {
            ignoreMainFrameErrorButFinishLoading()
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            cancelMainFrameTimeout()
            cancelCommitVisibleFallback()
            val failingUrl = request.url?.toString()
            if (shouldHandleMainFrameError(view, failingUrl)) {
                handleErrorState()
            } else {
                ignoreMainFrameErrorButFinishLoading()
            }
        }
    }

    private fun handleErrorState() {
        // 不立即显示错误页，先静默自动恢复。
        bbsPageState.requestRecoveryBeforeShowingError()
    }

    private fun ignoreMainFrameErrorButFinishLoading() {
        bbsPageState.finishIgnoredLoadError()
        GlobalData.webProgress.value = 100
    }

    private fun scheduleCommitVisibleFallback(view: WebView?, url: String?) {
        cancelCommitVisibleFallback()
        commitVisibleFallbackRunnable = Runnable {
            val currentProgress = try { view?.progress ?: 0 } catch (_: Throwable) { 0 }
            val visibleUrl = url ?: try { view?.url } catch (_: Throwable) { null }
            if (bbsPageState.isLoading &&
                !bbsPageState.isErrorState &&
                !bbsPageState.showLoadError &&
                bbsPageState.hasMainFrameCommitted &&
                currentProgress >= 100 &&
                bbsPageState.isUsableBbsUrl(visibleUrl)
            ) {
                bbsPageState.markLoadSucceeded(visibleUrl)
                GlobalData.webProgress.value = 100
            }
            commitVisibleFallbackRunnable = null
        }
        mainHandler.postDelayed(commitVisibleFallbackRunnable!!, 1200L)
    }

    private fun cancelCommitVisibleFallback() {
        commitVisibleFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        commitVisibleFallbackRunnable = null
    }

    private fun startMainFrameTimeout(view: WebView?, url: String?) {
        cancelMainFrameTimeout()
        cancelCommitVisibleFallback()
        mainFrameTimeoutRunnable = Runnable {
            if (bbsPageState.isLoading && !url.isNullOrBlank()) {
                view?.stopLoading()
                if (isYamiboUrl(url)) {
                    bbsPageState.requestRecoveryBeforeShowingError()
                } else {
                    bbsPageState.finishIgnoredLoadError()
                }
            }
        }
        mainHandler.postDelayed(mainFrameTimeoutRunnable!!, 15_000L)
    }

    private fun cancelMainFrameTimeout() {
        mainFrameTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        mainFrameTimeoutRunnable = null
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("RestrictedApi", "JavascriptInterface")
@Composable
fun BBSPage(
    webView: WebView,
    isSelected: Boolean,
    navController: NavController,
    bbsPageState: BBSPageState,
    pauseScheduler: BbsWebViewPauseScheduler
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

    val canConvertToReader = remember(bbsPageState.currentUrl, bbsPageState.pageTitle) {
        ReaderModeDetector.canConvertToReaderMode(bbsPageState.currentUrl, bbsPageState.pageTitle)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    ActivityWebViewLifecycleObserver(webView)
    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    val fullscreenApi = remember { FullscreenApi() }
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
    fullscreenApi.onCopyLink = { title, url ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("yamibo_link", "$title\n$url")
        clipboard.setPrimaryClip(clip)
        GlobalData.lastClipboardUrl = url
        YamiboToast.show(message = "已复制链接")
    }

    val nativeMangaApi = remember { NativeMangaJSInterface() }

    fun resumeBbsWebViewAfterChildPage() {
        pauseScheduler.cancel()
        try {
            webView.onResume()
            webView.resumeTimers()
            // 从 NativeMangaPage / ReaderPage 返回时，不重新加载页面，只恢复 WebView 的定时器与点击脚本。
            webView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
            webView.evaluateJavascript(PageJsScripts.RELOAD_BROKEN_IMAGES_JS, null)
            (webView.webViewClient as? BBSGlobalWebViewClient)?.forceInjectMangaJs(webView)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    nativeMangaApi.onGoBack = {
        activity?.onBackPressedDispatcher?.onBackPressed()
    }
    nativeMangaApi.onTriggerManga = { urlsJoined, clickedIndex, title ->
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlResult ->
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

                withContext(Dispatchers.Main) {
                    GlobalData.tempMangaUrls = urls
                    GlobalData.tempMangaIndex = safeClickedIndex
                    GlobalData.tempHtml = cleanHtml
                    GlobalData.tempTitle = title

                    // 不要 freeze / window.stop / stopLoading / onPause。
                    // 否则从 NativeMangaPage 返回时，原 WebView 可能停在半冻结状态，表现为 JS / 点击失效。
                    resumeBbsWebViewAfterChildPage()

                    webView.evaluateJavascript(PageJsScripts.REMOVE_TRANSITION_STYLE_JS, null)
                    autoOpenMangaMode = false
                    val passUrl = bbsPageState.currentUrl ?: indexUrl
                    val encodedUrl = URLEncoder.encode(passUrl, "utf-8")
                    navController.navigate("NativeMangaPage?url=$encodedUrl")
                }
            }
        }
    }
    val searchNavApi = remember {
        object {
            @JavascriptInterface
            fun navigateToPost(url: String) {
                Handler(Looper.getMainLooper()).post {
                    GlobalData.pendingClipboardUrl.value = url
                    GlobalData.lastClipboardUrl = url
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

    val pendingUrl by GlobalData.pendingClipboardUrl.collectAsState()
    LaunchedEffect(pendingUrl) {
        val url = pendingUrl ?: return@LaunchedEffect
        webView.loadUrl(url)
        GlobalData.pendingClipboardUrl.value = null
    }

    LaunchedEffect(bbsPageState.isLoading) {
        if (!bbsPageState.isLoading && autoOpenMangaMode) {
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

    fun startLoadTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(10_000L)
            if (bbsPageState.isLoading) {
                webView.stopLoading()
                isPullRefreshing = false

                if (bbsPageState.isAutoRecoveringBeforeError || bbsPageState.needsResumeRecovery) {
                    // 自动恢复也失败了，才显示错误页，并清掉恢复标记，避免错误页被 shouldDisplayLoadError 压住。
                    bbsPageState.failRecoveryBeforeShowingError()
                } else {
                    bbsPageState.showLoadErrorNow()
                }
            }
        }
    }

    fun loadUrlWithTimeout(url: String, blocking: Boolean, clearSuccessfulContent: Boolean) {
        if (blocking) {
            bbsPageState.markBlockingLoadRequested(url, clearSuccessfulContent = clearSuccessfulContent)
        } else {
            bbsPageState.markLoadRequested(url, clearSuccessfulContent = clearSuccessfulContent)
        }
        retryCount = 0
        CookieManager.getInstance().setCookie(url, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

        startLoadTimeout()
        try {
            webView.loadUrl(url)
        } catch (_: Throwable) {
            bbsPageState.requestRecoveryBeforeShowingError()
            (context as? org.shirakawatyu.yamibo.novel.MainActivity)
                ?.recreateBbsWebViewAfterRendererGone(webView)
        }
    }

    startLoading = { url: String ->
        loadUrlWithTimeout(
            url = url,
            blocking = !bbsPageState.hasSuccessfullyLoaded || bbsPageState.surfaceTrust == BbsSurfaceTrust.UntrustedBlocking,
            clearSuccessfulContent = false
        )
    }

    fun startBlockingLoading(url: String, clearSuccessfulContent: Boolean = true) {
        loadUrlWithTimeout(url = url, blocking = true, clearSuccessfulContent = clearSuccessfulContent)
    }

    fun reloadCurrentPageWithTimeout() {
        val targetUrl = selectBbsRecoveryUrl(bbsPageState, webView, mobileIndexUrl)
        bbsPageState.markBlockingLoadRequested(targetUrl, clearSuccessfulContent = true)
        retryCount = 0
        CookieManager.getInstance().setCookie(targetUrl, GlobalData.currentCookie)
        CookieManager.getInstance().flush()

        startLoadTimeout()
        val currentWebViewUrl = try {
            webView.url
        } catch (_: Throwable) {
            null
        }

        if (bbsPageState.isUsableBbsUrl(currentWebViewUrl)) {
            webView.reload()
        } else {
            webView.loadUrl(targetUrl)
        }
    }

    val isNetworkAvailable by remember {
        NetworkMonitor.observeNetwork(context)
    }.collectAsState(initial = false)

    fun runSilentResumeRepair() {
        // 30 秒以上后台恢复不再等同于显示骨架屏。
        // 只有 Native 快速门控已经确认没有可信旧内容，或 JS probe 明确 fatal，才升级为 blocking recovery。
        val targetUrl = selectBbsRecoveryUrl(bbsPageState, webView, mobileIndexUrl)

        val currentWebViewUrl = try {
            webView.url
        } catch (_: Throwable) {
            null
        }

        if (!bbsPageState.canTrustLastVisibleSurface(currentWebViewUrl)) {
            bbsPageState.beginBlockingRecovery(
                mode = bbsPageState.resumeRecoveryMode.takeUnless { it == BbsResumeRecoveryMode.None }
                    ?: BbsResumeRecoveryMode.HealthCheck,
                reason = bbsPageState.resumeRecoveryReason,
                clearSuccessfulContent = true
            )
            startBlockingLoading(targetUrl, clearSuccessfulContent = true)
            return
        }

        bbsPageState.beginSilentResumeRepair()

        // 先无条件做幂等 repair：这些不会触发主框架 reload，也不会打断用户。
        try {
            webView.evaluateJavascript(PageJsScripts.BBS_COMMIT_BOOTSTRAP_JS, null)
            if (GlobalData.isDarkMode.value || GlobalData.lightModeTheme.value > 0) {
                webView.evaluateJavascript(
                    PageJsScripts.getThemeSetJs(
                        GlobalData.isDarkMode.value,
                        GlobalData.darkModeTheme.value,
                        GlobalData.lightModeTheme.value
                    ), null
                )
            }
            webView.evaluateJavascript(PageJsScripts.RELOAD_BROKEN_IMAGES_JS, null)
            webView.evaluateJavascript(PageJsScripts.BBS_MANGA_REINJECT_JS, null)
        } catch (_: Throwable) {
            // evaluateJavascript 抛异常通常意味着 renderer/WebView 已不可用，直接升级。
            bbsPageState.beginBlockingRecovery(clearSuccessfulContent = true)
            startBlockingLoading(targetUrl, clearSuccessfulContent = true)
            return
        }

        var probeFinished = false
        val probeTimeoutJob = scope.launch {
            delay(1_800L)
            if (probeFinished || !bbsPageState.needsResumeRecovery) return@launch
            probeFinished = true

            // JS probe 超时只做记录，不降级为骨架屏或 reload。
            // 超时只能证明 Native 没及时收到回调，不能证明页面坏了。
            // 页面内 visibility agent 已在 commit 时预装，会自主修复。
            bbsPageState.noteSilentProbeTimeout()
        }

        webView.evaluateJavascript(BbsResumeHealthAgent.RESUME_REPAIR_AND_PROBE_JS) { result ->
            if (probeFinished) return@evaluateJavascript
            probeFinished = true
            probeTimeoutJob.cancel()

            val snapshot = BbsResumeHealthAgent.parse(result)
            val latestUrl = try { webView.url } catch (_: Throwable) { currentWebViewUrl }
            val latestTitle = try { webView.title } catch (_: Throwable) { null }
            bbsPageState.updatePageSnapshot(latestUrl ?: snapshot.href, latestTitle)

            when (snapshot.status) {
                BbsResumeHealthAgent.Status.Healthy -> {
                    if (bbsPageState.isUsableBbsUrl(snapshot.href ?: latestUrl)) {
                        bbsPageState.markLoadSucceeded(snapshot.href ?: latestUrl)
                    } else {
                        bbsPageState.finishSilentResumeRepair(healthy = false)
                    }
                }
                BbsResumeHealthAgent.Status.Unknown -> {
                    // Unknown 可能只是 DOM 暂未就绪、renderer 恢复延迟等。
                    // 页面继续显示，不 reload，不盖骨架。
                    bbsPageState.finishSilentResumeRepair(healthy = false)
                }
                BbsResumeHealthAgent.Status.Fatal -> {
                    // 只有明确 Fatal 才升级：骨架屏接管 + reload。
                    bbsPageState.beginBlockingRecovery(
                        mode = BbsResumeRecoveryMode.Reload,
                        reason = bbsPageState.resumeRecoveryReason,
                        clearSuccessfulContent = true
                    )
                    startBlockingLoading(targetUrl, clearSuccessfulContent = true)
                }
            }
        }
    }

    fun recoverBbsWebViewAfterResume() {
        if (!isSelected) return
        if (!bbsPageState.needsResumeRecovery &&
            !bbsPageState.isErrorState &&
            !bbsPageState.shouldDisplayLoadError
        ) {
            return
        }

        try {
            pauseScheduler.cancel()
            webView.onResume()
            webView.resumeTimers()

            val currentWebViewUrl = try {
                webView.url
            } catch (_: Throwable) {
                null
            }

            val mustBlock = bbsPageState.isErrorState ||
                    bbsPageState.showLoadError ||
                    !bbsPageState.hasSuccessfullyLoaded ||
                    !bbsPageState.isUsableBbsUrl(currentWebViewUrl) ||
                    !bbsPageState.canTrustLastVisibleSurface(currentWebViewUrl)

            val effectiveMode = when {
                mustBlock -> BbsResumeRecoveryMode.Reload
                else -> bbsPageState.resumeRecoveryMode
            }

            when (effectiveMode) {
                BbsResumeRecoveryMode.Reload -> {
                    if (mustBlock) {
                        bbsPageState.beginBlockingRecovery(
                            mode = BbsResumeRecoveryMode.Reload,
                            reason = bbsPageState.resumeRecoveryReason,
                            clearSuccessfulContent = true
                        )
                        startBlockingLoading(
                            selectBbsRecoveryUrl(bbsPageState, webView, mobileIndexUrl),
                            clearSuccessfulContent = true
                        )
                    } else {
                        // 显式 Reload 请求但旧页面可信：先静默 repair/probe，只有 fatal 才真的 reload。
                        runSilentResumeRepair()
                    }
                }
                BbsResumeRecoveryMode.HealthCheck,
                BbsResumeRecoveryMode.None -> runSilentResumeRepair()
            }
        } catch (_: Throwable) {
            bbsPageState.requestRecoveryBeforeShowingError()
            (context as? org.shirakawatyu.yamibo.novel.MainActivity)
                ?.recreateBbsWebViewAfterRendererGone(webView)
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeBbsWebViewAfterChildPage()

                if (bbsPageState.isErrorState || bbsPageState.showLoadError) {
                    bbsPageState.requestResumeRecovery()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 网络恢复时，如果错误页正在显示（自动恢复已失败），重新尝试恢复
    LaunchedEffect(isNetworkAvailable) {
        if (isNetworkAvailable &&
            bbsPageState.autoRecoveryFailed &&
            bbsPageState.showLoadError
        ) {
            bbsPageState.requestRecoveryBeforeShowingError()
        }
    }

    LaunchedEffect(
        isSelected,
        isNetworkAvailable,
        bbsPageState.needsResumeRecovery,
        bbsPageState.resumeRecoveryToken,
        bbsPageState.autoRecoveryToken,
        webView
    ) {
        if (isSelected && bbsPageState.needsResumeRecovery) {
            val throttleDelayMs = bbsPageState.resumeRecoveryThrottleDelayMs()
            if (throttleDelayMs > 0L) {
                delay(throttleDelayMs)
            }
            recoverBbsWebViewAfterResume()
        }
    }
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            webView.evaluateJavascript(PageJsScripts.CLEANUP_FULLSCREEN_JS, null)
            GlobalData.webProgress.value = 100
        } else {
            if (autoOpenMangaMode) {
                autoOpenMangaMode = false
            }
            bbsPageState.currentUrl?.let { url ->
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
        if (!isSelected) return@LaunchedEffect

        val currentWebViewUrl = try {
            webView.url
        } catch (_: Throwable) {
            null
        }

        if (!bbsPageState.hasSuccessfullyLoaded &&
            !bbsPageState.hasRequestedInitialLoad &&
            !bbsPageState.needsResumeRecovery &&
            !bbsPageState.showLoadError &&
            !bbsPageState.isAutoRecoveringBeforeError &&
            !bbsPageState.isUsableBbsUrl(currentWebViewUrl)
        ) {
            startLoading(mobileIndexUrl)
        }
    }
    LaunchedEffect(Unit) {
        bottomNavBarVM.refreshEvent.collect { route ->
            if (route != "BBSPage") return@collect

            isPullRefreshing = true
            val curl = webView.url
            if (!curl.isNullOrEmpty() && curl != "about:blank") {
                bbsPageState.markLoadRequested(curl)
                retryCount = 0

                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(10_000L)
                    if (bbsPageState.isLoading) {
                        webView.stopLoading()
                        isPullRefreshing = false

                        if (bbsPageState.isAutoRecoveringBeforeError || bbsPageState.needsResumeRecovery) {
                            bbsPageState.failRecoveryBeforeShowingError()
                        } else {
                            bbsPageState.showLoadErrorNow()
                        }
                    }
                }
                webView.reload()
            } else {
                startLoading(mobileIndexUrl)
            }
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.goHomeEvent.collect { route ->
            if (route == "BBSPage") {
                startLoading(mobileIndexUrl)
            }
        }
    }

    LaunchedEffect(Unit) {
        bottomNavBarVM.darkModeEvent.collect { _ ->
            val js = PageJsScripts.getThemeSetJs(
                GlobalData.isDarkMode.value,
                GlobalData.darkModeTheme.value,
                GlobalData.lightModeTheme.value
            )
            webView.evaluateJavascript(js, null)
        }
    }

    DisposableEffect(webView, isSelected) {

        if (isSelected) {
            canGoBack = webView.canGoBack()
            resumeBbsWebViewAfterChildPage()
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
                    for (i in 0 until 20) {
                        val cookieManager = CookieManager.getInstance()
                        val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""
                        val currentLoginState = isLoggedIn(currentCookie)

                        if (bbsPageState.isLoading || bbsPageState.showLoadError) {
                            bbsPageState.updateLoginState(currentLoginState)
                        } else if (bbsPageState.lastLoginState != null && bbsPageState.lastLoginState != currentLoginState) {
                            bbsPageState.updateLoginState(currentLoginState)
                            startLoading(BBSGlobalWebViewClient.MOBILE_INDEX_URL)
                            break
                        } else {
                            bbsPageState.updateLoginState(currentLoginState)
                        }
                        delay(500)
                    }
                }
            }
    }

    BackHandler(enabled = true) {
        val isHomepage =
            bbsPageState.currentUrl == indexUrl || bbsPageState.currentUrl == mobileIndexUrl ||
                    bbsPageState.currentUrl == bbsUrl || bbsPageState.currentUrl == baseBbsUrl ||
                    (bbsPageState.currentUrl?.startsWith("https://bbs.yamibo.com/forum.php") == true && bbsPageState.currentUrl?.contains(
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
    val topSpacerColor = if (isFullscreen) Color.Black else darkThemeColor(YamiboColors.primary) { statusBar }
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
                modifier = Modifier.fillMaxSize(),
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
                        webView.addJavascriptInterface(searchNavApi, "AndroidSearchNav")
                        bbsPageState.markBbsContainerMounted()
                    }
                },
                update = { container ->
                    if (webView.parent !== container) {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        container.removeAllViews()
                        container.addView(
                            webView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }

                    bbsPageState.markBbsContainerMounted()

                    webView.requestLayout()
                    webView.invalidate()

                    canGoBack = try {
                        webView.canGoBack()
                    } catch (_: Throwable) {
                        false
                    }
                    val snapshotUrl = try {
                        webView.url
                    } catch (_: Throwable) {
                        null
                    }
                    val snapshotTitle = try {
                        webView.title
                    } catch (_: Throwable) {
                        null
                    }
                    bbsPageState.updatePageSnapshot(snapshotUrl, snapshotTitle)
                },
                onRelease = {
                    val nextRoute = navController.currentDestination?.route.orEmpty()
                    val keepAliveForChildPage =
                        nextRoute.startsWith("NativeMangaPage") || nextRoute.startsWith("ReaderPage")

                    if (keepAliveForChildPage) {
                        pauseScheduler.cancel()
                    } else {
                        pauseScheduler.schedule(webView)
                    }
                }
            )

            if (bbsPageState.shouldDisplayLoadError) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
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

            val shouldShowLoadingCover =
                bbsPageState.shouldShowBbsLoadingCover &&
                        !isPullRefreshing &&
                        !bbsPageState.shouldDisplayLoadError

            if (shouldShowLoadingCover) {
                SideEffect {
                    bbsPageState.markBbsLoadingCoverMounted()
                }
                BbsSkeletonScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                )
            }

            ReaderModeFAB(
                visible = canConvertToReader && !bbsPageState.isLoading && !bbsPageState.shouldDisplayLoadError && !isFullscreenState.value,
                onClick = {
                    bbsPageState.currentUrl?.let { url ->
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
