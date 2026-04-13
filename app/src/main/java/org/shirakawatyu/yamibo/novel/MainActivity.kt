package org.shirakawatyu.yamibo.novel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.page.*
import org.shirakawatyu.yamibo.novel.ui.state.BBSPageState
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.theme._300文学Theme
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.BottomNavBar
import org.shirakawatyu.yamibo.novel.util.AutoSignManager
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import java.net.URLDecoder

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cookies")

class MainActivity : ComponentActivity() {

    var bbsWebViewState by mutableStateOf<WebView?>(null)
        private set
    private var backgroundStopJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        uploadMessage?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        uploadMessage = null
    }
    private val customWebChromeClient by lazy { createWebChromeClient() }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                GlobalData.webProgress.value = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (uploadMessage != null) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                }
                uploadMessage = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        fileChooserLauncher.launch(intent)
                    } else {
                        uploadMessage = null
                        return false
                    }
                } catch (_: Exception) {
                    uploadMessage = null
                    return false
                }
                return true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        GlobalData.dataStore = applicationContext.dataStore
        GlobalData.displayMetrics = resources.displayMetrics
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val layoutParams = window.attributes
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = layoutParams
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.statusBarColor = Color.TRANSPARENT

        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (bbsWebViewState == null) {
                    bbsWebViewState = createBbsWebView(this@MainActivity, customWebChromeClient)
                }
            }
            App(bbsWebView = bbsWebViewState, webChromeClient = customWebChromeClient)
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundStopJob?.cancel()
        backgroundStopJob = null

        if (bbsWebViewState == null) {
            // 兜底创建
            bbsWebViewState = createBbsWebView(this, customWebChromeClient)
        } else {
            bbsWebViewState?.onResume()
            bbsWebViewState?.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        bbsWebViewState?.onPause()

        backgroundStopJob?.cancel()
        backgroundStopJob = mainScope.launch {
            delay(600_000L) // 10分钟
            bbsWebViewState?.apply {
                (parent as? ViewGroup)?.removeView(this)
                removeAllViews()
                destroy()
            }
            bbsWebViewState = null
            BBSPageState.hasSuccessfullyLoaded = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        bbsWebViewState?.apply {
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        bbsWebViewState = null

        BBSPageState.hasSuccessfullyLoaded = false
        GlobalData.isAppInitialized = false
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createBbsWebView(context: Context, chromeClient: WebChromeClient? = null): WebView {
    BBSPageState.hasSuccessfullyLoaded = false

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            domStorageEnabled = true
        }
        webViewClient = BBSGlobalWebViewClient()
        webChromeClient = chromeClient ?: GlobalData.webChromeClient

        resumeTimers()
        onResume()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun App(bbsWebView: WebView?, webChromeClient: WebChromeClient) {
    val isAppInitialized = GlobalData.isAppInitialized
    val context = LocalContext.current
    var isRouteLoaded by remember { mutableStateOf(false) }
    var startPage by remember { mutableStateOf("BBSPage") }

    LaunchedEffect(Unit) {
        SettingsUtil.getHomePage {
            startPage = it
            isRouteLoaded = true
        }

        if (!GlobalData.isAppInitialized) {
            try {
                GlobalData.currentCookie = GlobalData.cookieFlow.first()
            } catch (_: Exception) {
                GlobalData.currentCookie = ""
            } finally {
                SettingsUtil.getDataSaverMode { isDataSaver ->
                    GlobalData.isDataSaverMode.value = isDataSaver
                }
                SettingsUtil.getFavoriteCollapseMode { isCollapsed ->
                    GlobalData.isFavoriteCollapsed.value = isCollapsed
                }
                GlobalData.isAppInitialized = true
//                自动签到（自用）
//                launch(Dispatchers.IO) {
//                    AutoSignManager.checkAndSignIfNeeded(context)
//                }
            }
        }
    }


    _300文学Theme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isAppInitialized && isRouteLoaded) {
                Box(contentAlignment = Alignment.TopCenter) {
                    val navController = rememberNavController()
                    val enterEasing = FastOutSlowInEasing
                    val exitEasing = FastOutLinearInEasing
                    val enterDuration = 380
                    val exitDuration = 300
                    val stateOwner = LocalViewModelStoreOwner.current
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val bottomNavBarVM: BottomNavBarVM = viewModel(stateOwner!!)
                    val context = LocalContext.current
                    val pageList = listOf("FavoritePage", "BBSPage", "MinePage")
                    val selectedItemIndex = pageList.indexOf(currentRoute).coerceAtLeast(0)
                    LaunchedEffect(bbsWebView, currentRoute) {
                        if (bbsWebView != null && !BBSPageState.hasSuccessfullyLoaded) {
                            try {
                                CookieManager.getInstance().setCookie("https://bbs.yamibo.com", GlobalData.currentCookie)
                                CookieManager.getInstance().flush()

                                if (currentRoute != null && currentRoute != "BBSPage") {
                                    bbsWebView.loadUrl("https://bbs.yamibo.com/forum.php?mobile=2")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(0f) }
                    val currentPaddingValue = navBarsPadding.value

                    LaunchedEffect(currentPaddingValue) {
                        if (currentPaddingValue > lockedNavHeightValue) {
                            lockedNavHeightValue = currentPaddingValue
                        }
                    }

                    val lockedNavHeight = lockedNavHeightValue.dp
                    Box(modifier = Modifier.fillMaxSize()) {
                        val statusBarColor = when {
                            currentRoute == "FavoritePage" -> YamiboColors.onSurface
                            currentRoute == "BBSPage" -> YamiboColors.primary
                            currentRoute == "MinePage" -> YamiboColors.primary
                            currentRoute?.startsWith("OtherWebPage") == true -> YamiboColors.primary
                            else -> null
                        }
                        if (statusBarColor != null) {
                            SetStatusBarColor(statusBarColor)
                        }
                        val topLevelRoutes = listOf("FavoritePage", "BBSPage", "MinePage")
                        NavHost(
                            modifier = Modifier.fillMaxSize(),
                            navController = navController,
                            startDestination = startPage
                        ) {
                            composable(
                                "FavoritePage",
                                enterTransition = {
                                    if (initialState.destination.route in topLevelRoutes) EnterTransition.None
                                    else fadeIn(tween(150))
                                },
                                exitTransition = {
                                    if (targetState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(enterDuration, easing = enterEasing))
                                    } else if (targetState.destination.route?.startsWith("NativeMangaPage") == true || targetState.destination.route in topLevelRoutes) {
                                        ExitTransition.None
                                    } else fadeOut(tween(150))
                                },
                                popEnterTransition = {
                                    if (initialState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(exitDuration, easing = exitEasing))
                                    } else if (initialState.destination.route?.startsWith("NativeMangaPage") == true || initialState.destination.route in topLevelRoutes) {
                                        EnterTransition.None
                                    } else fadeIn(tween(150))
                                },
                                popExitTransition = {
                                    if (targetState.destination.route in topLevelRoutes) ExitTransition.None
                                    else fadeOut(tween(150))
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    FavoritePage(
                                        viewModel(stateOwner, factory = ViewModelFactory(context.applicationContext)),
                                        navController
                                    )
                                    Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lockedNavHeight)) {
                                        BottomNavBar(navController, "FavoritePage", bottomNavBarVM)
                                    }

                                    val isContentPage = currentRoute?.run { startsWith("ReaderPage") || startsWith("NativeMangaPage") || startsWith("MangaWebPage") || startsWith("OtherWebPage") } == true
                                    val maskAlpha by animateFloatAsState(
                                        targetValue = if (isContentPage) 0.5f else 0f,
                                        animationSpec = tween(if (isContentPage) enterDuration else exitDuration, easing = if (isContentPage) enterEasing else exitEasing),
                                        label = "FavoriteMask"
                                    )
                                    if (maskAlpha > 0f) {
                                        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = maskAlpha)))
                                    }
                                }
                            }
                            composable(
                                "BBSPage",
                                enterTransition = {
                                    if (initialState.destination.route in topLevelRoutes) EnterTransition.None
                                    else fadeIn(tween(150))
                                },
                                exitTransition = {
                                    if (targetState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(enterDuration, easing = enterEasing))
                                    } else if (targetState.destination.route in topLevelRoutes) {
                                        ExitTransition.None
                                    } else fadeOut(tween(150))
                                },
                                popEnterTransition = {
                                    if (initialState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(exitDuration, easing = exitEasing))
                                    } else if (initialState.destination.route?.startsWith("NativeMangaPage") == true || initialState.destination.route in topLevelRoutes) {
                                        EnterTransition.None
                                    } else fadeIn(tween(150))
                                },
                                popExitTransition = {
                                    if (targetState.destination.route in topLevelRoutes) ExitTransition.None
                                    else fadeOut(tween(150))
                                }
                            ) {
                                if (bbsWebView != null) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        BBSPage(
                                            webView = bbsWebView,
                                            isSelected = selectedItemIndex == 1,
                                            navController = navController
                                        )
                                        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lockedNavHeight)) {
                                            BottomNavBar(navController, "BBSPage", bottomNavBarVM)
                                        }

                                        val isContentPage = currentRoute?.run { startsWith("ReaderPage") || startsWith("NativeMangaPage") || startsWith("MangaWebPage") || startsWith("OtherWebPage") } == true
                                        val maskAlpha by animateFloatAsState(
                                            targetValue = if (isContentPage) 0.5f else 0f,
                                            animationSpec = tween(if (isContentPage) enterDuration else exitDuration, easing = if (isContentPage) enterEasing else exitEasing),
                                            label = "BBSMask"
                                        )
                                        if (maskAlpha > 0f) {
                                            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = maskAlpha)))
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = YamiboColors.primary)
                                    }
                                }
                            }
                            composable(
                                "MinePage",
                                enterTransition = {
                                    if (initialState.destination.route in topLevelRoutes) EnterTransition.None
                                    else fadeIn(tween(150))
                                },
                                exitTransition = {
                                    if (targetState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(enterDuration, easing = enterEasing))
                                    } else if (targetState.destination.route in topLevelRoutes) {
                                        ExitTransition.None
                                    } else fadeOut(tween(150))
                                },
                                popEnterTransition = {
                                    if (initialState.destination.route?.startsWith("ReaderPage") == true) {
                                        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(exitDuration, easing = exitEasing))
                                    } else if (initialState.destination.route?.startsWith("NativeMangaPage") == true || initialState.destination.route in topLevelRoutes) {
                                        EnterTransition.None
                                    } else fadeIn(tween(150))
                                },
                                popExitTransition = {
                                    if (targetState.destination.route in topLevelRoutes) ExitTransition.None
                                    else fadeOut(tween(150))
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    MinePage(
                                        isSelected = selectedItemIndex == 2,
                                        navController = navController,
                                        webChromeClient = webChromeClient
                                    )
                                    Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lockedNavHeight)) {
                                        BottomNavBar(navController, "MinePage", bottomNavBarVM)
                                    }

                                    val isContentPage = currentRoute?.run { startsWith("ReaderPage") || startsWith("NativeMangaPage") || startsWith("MangaWebPage") || startsWith("OtherWebPage") } == true
                                    val maskAlpha by animateFloatAsState(
                                        targetValue = if (isContentPage) 0.5f else 0f,
                                        animationSpec = tween(if (isContentPage) enterDuration else exitDuration, easing = if (isContentPage) enterEasing else exitEasing),
                                        label = "MineMask"
                                    )
                                    if (maskAlpha > 0f) {
                                        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = maskAlpha)))
                                    }
                                }
                            }
                            composable(
                                "ReaderPage/{passageUrl}",
                                arguments = listOf(navArgument("passageUrl") { type = NavType.StringType }),
                                enterTransition = {
                                    slideIntoContainer(towards = AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(enterDuration, easing = enterEasing))
                                },
                                popExitTransition = {
                                    slideOutOfContainer(towards = AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(exitDuration, easing = exitEasing))
                                }
                            ) {
                                it.arguments?.getString("passageUrl")?.let { url ->
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        ReaderPage(url = URLDecoder.decode(url, "utf-8"), navController = navController)
                                    }
                                }
                            }
                            composable(
                                "MangaWebPage/{url}/{originalUrl}?fastForward={fastForward}&initialPage={initialPage}",
                                arguments = listOf(
                                    navArgument("url") { type = NavType.StringType },
                                    navArgument("originalUrl") { type = NavType.StringType },
                                    navArgument("fastForward") { type = NavType.BoolType; defaultValue = false },
                                    navArgument("initialPage") { type = NavType.IntType; defaultValue = 0 }
                                ),
                                enterTransition = {
                                    if (initialState.destination.route?.startsWith("ProbingPage") == true || initialState.destination.route?.startsWith("FavoritePage") == true) EnterTransition.None
                                    else fadeIn(tween(150))
                                },
                                exitTransition = {
                                    if (targetState.destination.route?.startsWith("NativeMangaPage") == true) ExitTransition.None
                                    else fadeOut(tween(150))
                                },
                                popEnterTransition = {
                                    if (initialState.destination.route?.startsWith("NativeMangaPage") == true) EnterTransition.None
                                    else fadeIn(tween(150))
                                }
                            ) {
                                val loadUrl = URLDecoder.decode(it.arguments?.getString("url") ?: "", "utf-8")
                                val originalUrl = URLDecoder.decode(it.arguments?.getString("originalUrl") ?: "", "utf-8")
                                val fastForward = it.arguments?.getBoolean("fastForward") ?: false
                                val initialPage = it.arguments?.getInt("initialPage") ?: 0

                                MangaWebPage(
                                    url = loadUrl,
                                    originalFavoriteUrl = originalUrl,
                                    navController = navController,
                                    webChromeClient = webChromeClient,
                                    isFastForward = fastForward,
                                    initialPage = initialPage
                                )
                            }
                            composable(
                                "OtherWebPage/{url}",
                                arguments = listOf(navArgument("url") { type = NavType.StringType })
                            ) {
                                it.arguments?.getString("url")?.let { url ->
                                    OtherWebPage(url = URLDecoder.decode(url, "utf-8"), navController = navController, webChromeClient = webChromeClient)
                                }
                            }
                            composable(
                                "ProbingPage/{url}",
                                arguments = listOf(navArgument("url") { type = NavType.StringType })
                            ) {
                                val url = URLDecoder.decode(it.arguments?.getString("url") ?: "", "utf-8")
                                ProbingPage(url, navController)
                            }
                            composable(
                                route = "NativeMangaPage?url={url}&originalUrl={originalUrl}",
                                arguments = listOf(
                                    navArgument("url") { defaultValue = "" },
                                    navArgument("originalUrl") { defaultValue = "" }
                                ),
                                enterTransition = {
                                    scaleIn(initialScale = 0.50f, animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = {
                                    scaleOut(targetScale = 0.8f, animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeOut(targetAlpha = 0.01f, animationSpec = tween(250, easing = FastOutSlowInEasing))
                                }
                            ) { backStackEntry ->
                                val url = backStackEntry.arguments?.getString("url") ?: ""
                                val originalUrl = backStackEntry.arguments?.getString("originalUrl")?.takeIf { it.isNotBlank() } ?: url

                                NativeMangaPage(url = url, originalUrl = originalUrl, navController = navController)
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YamiboColors.primary)
                }
            }
        }
    }
}