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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
    private val onStateChange: (Boolean) -> Unit,
    private val onUiStateChange: (Boolean) -> Unit
) {
    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post { onStateChange(isFullscreen) }
    }

    @JavascriptInterface
    fun notifyUi(isUiVisible: Boolean) {
        Handler(Looper.getMainLooper()).post { onUiStateChange(isUiVisible) }
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
    var showChapterList by remember { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl)
    }
    // ----- 全屏状态控制 -----
    val view = LocalView.current
    val isFullscreenState = remember { mutableStateOf(false) }
    val isFullscreenUiVisible = remember { mutableStateOf(true) }
    // 强制获取ViewModel
    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)

    // 监听全屏状态并切换UI
    LaunchedEffect(isFullscreenState.value) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)

        if (isFullscreenState.value) {
            // 进入全屏：隐藏手机顶部状态栏、底部系统小白条
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 允许用户从边缘滑动临时呼出状态栏
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 隐藏App的底部导航栏
            bottomNavBarVM.setBottomNavBarVisibility(false)
        } else {
            // 退出全屏：恢复所有 UI
            controller.show(WindowInsetsCompat.Type.systemBars())
            bottomNavBarVM.setBottomNavBarVisibility(true)
            showChapterList = false
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
                // 页面内容已可见，立即停止加载圈
                if (isLoading) {
                    timeoutJob?.cancel()
                    retryCount = 0
                    isLoading = false
                    showLoadError = false
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
                }
                webView.addJavascriptInterface(
                    FullscreenApi(
                        onStateChange = { isFullscreen ->
                            isFullscreenState.value = isFullscreen
                            if (!isFullscreen) isFullscreenUiVisible.value = true
                        },
                        onUiStateChange = { isUiVisible ->
                            isFullscreenUiVisible.value = isUiVisible
                        }
                    ), "AndroidFullscreen")
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

        AnimatedVisibility(
            // 控制条件：只有在全屏模式下，且顶部菜单栏可见时，才显示目录按钮
            visible = isFullscreenState.value && isFullscreenUiVisible.value,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                initialOffsetY = { it / 2 }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            Button(
                onClick = {
                    showChapterList = true
                },
                modifier = Modifier.fillMaxWidth(0.5f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "目录",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("目录")
            }
        }
        if (showChapterList) {
            MangaChapterPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                onDismiss = { showChapterList = false },
                onChapterClick = { chapter ->
                    showChapterList = false
                    // TODO: 跳转
                }
            )
        }
    }
}
