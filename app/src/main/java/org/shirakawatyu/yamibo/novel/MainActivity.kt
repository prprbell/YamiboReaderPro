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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.page.BBSGlobalWebViewClient
import org.shirakawatyu.yamibo.novel.ui.page.BBSPage
import org.shirakawatyu.yamibo.novel.ui.page.FavoritePage
import org.shirakawatyu.yamibo.novel.ui.page.MangaWebPage
import org.shirakawatyu.yamibo.novel.ui.page.MinePage
import org.shirakawatyu.yamibo.novel.ui.page.NativeMangaPage
import org.shirakawatyu.yamibo.novel.ui.page.OtherWebPage
import org.shirakawatyu.yamibo.novel.ui.page.ProbingPage
import org.shirakawatyu.yamibo.novel.ui.page.ReaderPage
import org.shirakawatyu.yamibo.novel.ui.state.BBSPageState
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.theme._300文学Theme
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM
import org.shirakawatyu.yamibo.novel.ui.vm.ViewModelFactory
import org.shirakawatyu.yamibo.novel.ui.widget.BbsSkeletonScreen
import org.shirakawatyu.yamibo.novel.ui.widget.BottomNavBar
import org.shirakawatyu.yamibo.novel.util.AccountSyncManager
import org.shirakawatyu.yamibo.novel.util.AutoSignManager
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.NetworkMonitor
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import java.net.URLDecoder
import kotlin.coroutines.resume

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
        val initialRoute = runBlocking {
            try {
                val prefs = applicationContext.dataStore.data.first()
                val saved = prefs[stringPreferencesKey("home_page")]
                if (saved.isNullOrBlank()) "BBSPage" else saved
            } catch (_: Exception) {
                "BBSPage"
            }
        }
        GlobalData.homePageRoute.value = initialRoute
        super.onCreate(savedInstanceState)

        val isRestoring = savedInstanceState != null

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

        if (bbsWebViewState == null) {
            bbsWebViewState = createBbsWebView(this, customWebChromeClient)
        }

        setContent {
            App(bbsWebView = bbsWebViewState, webChromeClient = customWebChromeClient, isRestoring = isRestoring)
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundStopJob?.cancel()
        backgroundStopJob = null

        if (bbsWebViewState == null) {
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
            BBSPageState.isLoading = false
            BBSPageState.isErrorState = false
            BBSPageState.showLoadError = false
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
        webViewClient = BBSGlobalWebViewClient(context)
        webChromeClient = chromeClient ?: GlobalData.webChromeClient

        resumeTimers()
        onResume()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun App(bbsWebView: WebView?, webChromeClient: WebChromeClient, isRestoring: Boolean = false) {
    val isAppInitialized = GlobalData.isAppInitialized
    val context = LocalContext.current
    val isNetworkAvailable by remember {
        NetworkMonitor.observeNetwork(context)
    }.collectAsState(initial = false)
    val homeRoute by GlobalData.homePageRoute.collectAsState()

    LaunchedEffect(Unit) {
        if (!GlobalData.isAppInitialized) {
            try {
                GlobalData.currentCookie = GlobalData.cookieFlow.first()
            } catch (_: Exception) {
                GlobalData.currentCookie = ""
            } finally {
                val route = suspendCancellableCoroutine { continuation ->
                    SettingsUtil.getHomePage {
                        continuation.resume(it)
                    }
                }
                GlobalData.homePageRoute.value = route
                SettingsUtil.getFavoriteCollapseMode { GlobalData.isFavoriteCollapsed.value = it }
                SettingsUtil.getCustomDnsMode { GlobalData.isCustomDnsEnabled.value = it }
                SettingsUtil.getClickToTopMode { GlobalData.isClickToTopEnabled.value = it }
                SettingsUtil.getAutoSignInMode { GlobalData.isAutoSignInEnabled.value = it }
                GlobalData.isAppInitialized = true
            }
        }
    }

    LaunchedEffect(isAppInitialized, isNetworkAvailable) {
        if (isAppInitialized && isNetworkAvailable && GlobalData.isAutoSignInEnabled.value) {
            launch(Dispatchers.IO) {
                val needsSign = AutoSignManager.needsSignIn()
                if (needsSign) {
                    delay(2000L)
                } else {
                    delay(8000L)
                }
                AutoSignManager.checkAndSignIfNeeded(context)
            }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, isAppInitialized, isNetworkAvailable) {
        if (!isAppInitialized || !isNetworkAvailable) return@DisposableEffect onDispose {}

        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch(Dispatchers.IO) {
                    AccountSyncManager.syncCookieAndCheckSign(context, "APP_RESUME")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        coroutineScope.launch(Dispatchers.IO) {
            AccountSyncManager.syncCookieAndCheckSign(context, "APP_INIT")
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    _300文学Theme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isAppInitialized) {
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
                    val selectedItemIndex = pageList.indexOf(currentRoute ?: homeRoute).coerceAtLeast(0)

                    LaunchedEffect(bbsWebView, isNetworkAvailable, homeRoute) {
                        if (bbsWebView != null && isNetworkAvailable && !BBSPageState.hasSuccessfullyLoaded) {
                            try {
                                CookieManager.getInstance().setCookie("https://bbs.yamibo.com", GlobalData.currentCookie)
                                CookieManager.getInstance().flush()
                                val targetUrl = BBSPageState.currentUrl?.takeIf { it.isNotBlank() && it != "about:blank" }
                                    ?: "https://bbs.yamibo.com/forum.php?mobile=2"
                                bbsWebView.loadUrl(targetUrl)
                                BBSPageState.isLoading = true
                                launch {
                                    delay(18000)
                                    if (BBSPageState.isLoading) {
                                        bbsWebView.stopLoading()
                                        BBSPageState.isErrorState = true
                                        BBSPageState.isLoading = false
                                        BBSPageState.showLoadError = true
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }


                    val density = androidx.compose.ui.platform.LocalDensity.current.density

                    val initialNavHeight = remember(context, density) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                                android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.displayCutout()
                            )
                            insets.bottom / density
                        } else {
                            0f
                        }
                    }

                    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val currentPaddingValue = navBarsPadding.value

                    var lockedNavHeightValue by rememberSaveable { mutableFloatStateOf(initialNavHeight) }

                    SideEffect {
                        if (currentPaddingValue > lockedNavHeightValue) {
                            lockedNavHeightValue = currentPaddingValue
                        }
                    }

                    val lockedNavHeight = maxOf(currentPaddingValue, lockedNavHeightValue).dp

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
                            startDestination = homeRoute
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
                                    } else if (initialState.destination.route?.startsWith("NativeMangaPage") == true) {
                                        EnterTransition.None
                                    } else if (initialState.destination.route in topLevelRoutes) {
                                        EnterTransition.None
                                    } else fadeIn(tween(150))
                                },
                                popExitTransition = {
                                    if (targetState.destination.route in topLevelRoutes) {
                                        ExitTransition.None
                                    } else fadeOut(tween(150))
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
                                        CircularProgressIndicator(color = YamiboColors.secondary)
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
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                val initStatusHeight = remember(context, density) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                        )
                        insets.top / density
                    } else {
                        24f
                    }
                }

                val currentTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
                val lockedTopPadding = maxOf(initStatusHeight, currentTopPadding).dp

                if (homeRoute == "BBSPage" && !isRestoring) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(lockedTopPadding)
                                .background(YamiboColors.primary)
                                .align(Alignment.TopCenter)
                                .zIndex(1f)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = lockedTopPadding)
                        ) {
                            BbsSkeletonScreen(modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isRestoring) {
                            CircularProgressIndicator(color = YamiboColors.secondary)
                        }
                    }
                }
            }
        }
    }
}