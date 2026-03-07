package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

private val hideCommand = """
    javascript:(function() {
        var style = document.createElement('style');
        style.innerHTML = '.my { display: none !important; }';
        document.head.appendChild(style);
    })()
""".trimIndent()

class FullscreenApiMine(
    private val onStateChange: (Boolean) -> Unit,
    private val onUiStateChange: (Boolean) -> Unit,
    private val onMangaActionDone: () -> Unit
) {
    @JavascriptInterface
    fun notify(isFullscreen: Boolean) {
        Handler(Looper.getMainLooper()).post {
            onStateChange(isFullscreen)
        }
    }

    @JavascriptInterface
    fun notifyUi(isUiVisible: Boolean) {
        Handler(Looper.getMainLooper()).post { onUiStateChange(isUiVisible) }
    }

    @JavascriptInterface
    fun notifyMangaActionDone() {
        Handler(Looper.getMainLooper()).post { onMangaActionDone() }
    }
}

/**
 * 个人中心，WebView每次访问时创建，离开时销毁
 *
 * @param isSelected 表示当前页面是否被选中，用于控制页面加载逻辑和状态更新。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MinePage(
    isSelected: Boolean,
    navController: NavController,
    webChromeClient: WebChromeClient
) {
    SetStatusBarColor(YamiboColors.primary)
    val mineUrl = "https://bbs.yamibo.com/home.php?mod=space&do=profile&mycenter=1&mobile=2"
    val bbsUrl = "https://bbs.yamibo.com/?mobile=2" // 你原来的定义
    val baseBbsUrl = "https://bbs.yamibo.com/"      // 根URL
    val indexUrl = "https://bbs.yamibo.com/forum.php" // 论坛主页

    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showLoadError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timeoutJob by remember { mutableStateOf<Job?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var showChapterList by remember { mutableStateOf(false) }
    var pendingNavigateUrl by remember { mutableStateOf<String?>(null) }
    var autoOpenMangaMode by remember { mutableStateOf(false) }

    val canConvertToReader = remember(currentUrl) {
        ReaderModeDetector.canConvertToReaderMode(currentUrl)
    }
    val mangaDirVM: MangaDirectoryVM = viewModel(
        factory = ViewModelFactory(LocalContext.current.applicationContext)
    )
    lateinit var startLoading: (webView: WebView, url: String) -> Unit

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
        showLoadError = false
        retryCount = 0

        runTimeout(webView) {
            Log.w("MinePage", "WebView loading timed out. Retrying...")
            webView.stopLoading()
            retryCount++

            runTimeout(webView) {
                Log.e("MinePage", "Retry timed out. Giving up.")
                isLoading = false
                showLoadError = true
                webView.stopLoading()
            }
            webView.reload()
        }

        webView.loadUrl(url)
    }
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    // ----- 全屏状态控制 -----
    val isFullscreenState = remember { mutableStateOf(false) }
    val isFullscreenUiVisible = remember { mutableStateOf(true) }

    val bottomNavBarVM: BottomNavBarVM =
        viewModel(viewModelStoreOwner = context as ComponentActivity)

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
    val mineWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = true
                javaScriptEnabled = true
            }
            addJavascriptInterface(
                FullscreenApiMine(
                    onStateChange = { isFullscreen ->
                        isFullscreenState.value = isFullscreen
                    },
                    onUiStateChange = { isUiVisible ->
                        isFullscreenUiVisible.value = isUiVisible
                    },
                    onMangaActionDone = {
                        autoOpenMangaMode = false
                    }
                ),
                "AndroidFullscreen"
            )
            this.webChromeClient = webChromeClient
        }
    }
    // 1. 专门控制系统 UI 显隐（合并了全屏状态和无缝切换的黑屏状态）
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
            showChapterList = false
        }
    }

    // 2. 统一处理全屏状态变化时的核心业务逻辑（脱下隐身衣、网页跳转、解析目录）
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            // 【核心修复 1】：退出大图时，务必撕掉网页的纯黑隐身衣！让原帖重新显示出来！
            mineWebView.evaluateJavascript(
                """
                var style = document.getElementById('manga-transition-style');
                if (style) style.remove();
            """.trimIndent(), null
            )

            // 【核心修复 2】：处理积压的“下一话”跳转任务
            pendingNavigateUrl?.let { url ->
                isLoading = true
                mineWebView.loadUrl(url)
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
                    val pageTitle = mineWebView.title ?: ""
                    mineWebView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { htmlResult ->
                        val cleanHtml = try {
                            JSON.parse(htmlResult) as? String ?: ""
                        } catch (e: Exception) {
                            htmlResult.trim('"').replace("\\u003C", "<").replace("\\\"", "\"")
                        }
                        if (cleanHtml.isNotBlank()) {
                            mangaDirVM.initDirectoryFromWeb(url, cleanHtml, pageTitle)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(mineWebView, isSelected) {
        mineWebView.webViewClient = object : YamiboWebViewClient() {
            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                canGoBack = view?.canGoBack() ?: false
                view?.loadUrl(hideCommand)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                canGoBack = view?.canGoBack() ?: false
            }

            @RequiresApi(Build.VERSION_CODES.M)
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

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutJob?.cancel()
                retryCount = 0
                isLoading = false
                showLoadError = false
                super.onPageFinished(view, url)
                currentUrl = url
                if (isSelected && view != null) {
                    val currentUrl = view.url ?: ""

                    val isHomepage = currentUrl == bbsUrl ||
                            currentUrl == baseBbsUrl ||
                            currentUrl == indexUrl
                    if (isHomepage) {
                        startLoading(view, mineUrl)
                    }
                }
                canGoBack = view?.canGoBack() ?: false
            }

            @RequiresApi(Build.VERSION_CODES.M)
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
                    }
                }
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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
                    }
                }
            }
        }

        if (isSelected && mineWebView.url == null) {
            startLoading(mineWebView, mineUrl)
        } else {
            canGoBack = mineWebView.canGoBack()
        }
    }
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            timeoutJob?.cancel()
            retryCount = 0
            isLoading = false
        }
    }
    // 1. 监听大图退出状态，执行积压的跳转任务
    LaunchedEffect(isFullscreenState.value) {
        if (!isFullscreenState.value) {
            // 大图已彻底关闭，可以安全加载新URL了
            pendingNavigateUrl?.let { url ->
                // 【核心修复】：在加载新 URL 前，必须手动将状态置为 true！
                // 这样新网页加载到 onPageCommitVisible 时，isLoading 变回 false，
                // 就能完美唤醒下面那个负责注入 JS 的 LaunchedEffect。
                isLoading = true

                mineWebView.loadUrl(url)
                pendingNavigateUrl = null
            }
        } else if (isFullscreenState.value && autoOpenMangaMode) {
            // 新页面的大图被成功打开了！关闭黑屏过渡层
            autoOpenMangaMode = false
        }
    }

    // 2. 监听页面加载完成，注入基于事件驱动的监听 JS
    LaunchedEffect(isLoading) {
        if (!isLoading && autoOpenMangaMode) {
            val clickJs = """
                (function() {
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
                            // 大图弹出来了！立刻停止探测并通知 Android
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
                        if (links.length > 0) {
                            var el = links[0];
                            el.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
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

            mineWebView.evaluateJavascript(clickJs, null)

            // Compose 层的安全网：防止极端情况下 JS 引擎崩溃或报错导致黑屏无法解除
            // 给它比 JS 多 1 秒的宽限期
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
    BackHandler(enabled = canGoBack) {
        mineWebView.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mineWebView },
            update = {
                canGoBack = it.canGoBack()
                currentUrl = it.url
            },
            onRelease = {
                timeoutJob?.cancel()
                (it.parent as? ViewGroup)?.removeView(it)
                it.stopLoading()
                it.destroy() // 销毁实例
            }
        )
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
                    startLoading(mineWebView, mineWebView.url ?: mineUrl)
                }) {
                    Text("重试")
                }
            }
        }

        // 局部加载指示器
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YamiboColors.secondary
            )
        }

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
            val currentDir = mangaDirVM.currentDirectory

            // 将底层数据转换为 UI 需要的展示格式
            val displayChapters = currentDir?.chapters?.map { item ->
                MangaChapter(
                    index = item.chapterNum.toInt(),
                    title = item.rawTitle,
                    isCurrent = currentUrl?.contains(item.url) == true, // 如果当前URL包含这章节的URL，则标亮
                    isRead = false
                )
            } ?: emptyList()

            MangaChapterPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                title = currentDir?.cleanBookName ?: "加载中...",
                chapters = displayChapters,
                isUpdating = mangaDirVM.isUpdatingDirectory,       // 绑定更新中状态
                cooldownSeconds = mangaDirVM.directoryCooldown,    // 绑定冷却倒计时
                onUpdateClick = { mangaDirVM.updateMangaDirectory() }, // 绑定更新点击事件
                onDismiss = { showChapterList = false },
                onChapterClick = { chapter ->
                    showChapterList = false
                    val targetUrl =
                        currentDir?.chapters?.find { it.chapterNum.toInt() == chapter.index }?.url
                    if (targetUrl != null) {
                        val finalUrl =
                            if (targetUrl.startsWith("http")) targetUrl else "https://bbs.yamibo.com/$targetUrl"
                        autoOpenMangaMode = true
                        // 暂存 URL 并触发网页回退
                        pendingNavigateUrl = finalUrl
                        mineWebView.evaluateJavascript("window.history.back();", null)
                    }
                }
            )
        }
        // 无缝切换漫画章节的黑屏遮罩层
        if (autoOpenMangaMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black) // 使用纯黑色，和 PhotoSwipe 完美衔接
                    // 拦截手势，防止用户在页面跳转切换期间乱点网页
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

