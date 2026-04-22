package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.PageJsScripts
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.WebViewPool
import java.net.URLDecoder
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProbingPage(url: String, navController: NavController) {
    SetStatusBarColor(Color.Black)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRedirecting by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val checkJs = remember { PageJsScripts.PROBING_CHECK_JS }

    LaunchedEffect(webViewRef) {
        val webView = webViewRef ?: return@LaunchedEffect
        var timeWaited = 0
        val checkInterval = 250L
        val maxWaitTime = 12000

        while (timeWaited < maxWaitTime && !isRedirecting) {
            kotlinx.coroutines.delay(checkInterval)
            timeWaited += checkInterval.toInt()

            withContext(Dispatchers.Main) {
                if (isRedirecting) return@withContext

                webView.evaluateJavascript(checkJs) { result ->
                    if (isRedirecting) return@evaluateJavascript

                    val cleanResult = result?.removeSurrounding("\"") ?: ""
                    if (cleanResult == "WAIT" || cleanResult.isBlank() || cleanResult == "null") {
                        return@evaluateJavascript
                    }

                    isRedirecting = true

                    val parts = cleanResult.split(":::")
                    val type = parts[0].toIntOrNull() ?: 3

                    scope.launch(Dispatchers.IO) {
                        val map = FavoriteUtil.getFavoriteMapSuspend()
                        map[url]?.let { fav ->
                            var changed = false
                            var newFav = fav

                            if (type == 1) {
                                val authorId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                                if (fav.type != 1 || fav.authorId != authorId) {
                                    newFav = newFav.copy(type = 1, authorId = authorId)
                                    changed = true
                                }

                                val encodedTitle = parts.getOrNull(2) ?: ""
                                var rawTitle = if (encodedTitle.isNotBlank()) URLDecoder.decode(encodedTitle, "UTF-8") else ""
                                rawTitle = rawTitle.replace(Regex("\\s+[-—–_]+\\s+.*?(文学区|小说区|译文区|百合会|论坛).*$"), "").trim()
                                if (rawTitle.isNotBlank() && newFav.title != rawTitle) {
                                    newFav = newFav.copy(title = rawTitle)
                                    changed = true
                                }
                            } else {
                                if (fav.type != type) {
                                    newFav = newFav.copy(type = type)
                                    changed = true
                                }
                            }

                            if (changed) {
                                FavoriteUtil.updateFavoriteSuspend(newFav)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val encoded = URLEncoder.encode(url, "utf-8")
                            if (type == 2) {
                                val title = URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                                val urlsJoined = parts.getOrNull(2) ?: ""
                                val htmlContent = URLDecoder.decode(parts.getOrNull(3) ?: "", "UTF-8")
                                val urlsList = urlsJoined.split("|||").filter { it.isNotBlank() }

                                GlobalData.tempMangaUrls = urlsList
                                GlobalData.tempTitle = title
                                GlobalData.tempHtml = htmlContent
                                GlobalData.tempMangaIndex = 0

                                navController.navigate("MangaWebPage/$encoded/$encoded?fastForward=true") {
                                    popUpTo("ProbingPage/{url}") { inclusive = true }
                                }
                                navController.navigate("NativeMangaPage?url=$encoded")
                            } else {
                                val route = when (type) {
                                    1 -> "ReaderPage/$encoded"
                                    else -> "OtherWebPage/$encoded"
                                }
                                navController.navigate(route) {
                                    popUpTo("ProbingPage/{url}") { inclusive = true }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 超时兜底逻辑
        if (!isRedirecting) {
            isRedirecting = true
            withContext(Dispatchers.Main) {
                val encoded = URLEncoder.encode(url, "utf-8")
                navController.navigate("OtherWebPage/$encoded") {
                    popUpTo("ProbingPage/{url}") { inclusive = true }
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = YamiboColors.primary)

            AndroidView(
                factory = {
                    WebViewPool.acquire(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            blockNetworkImage = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                return YamiboRetrofit.proxyWebViewResource(request!!)
                                    ?: super.shouldInterceptRequest(view, request)
                            }
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                            ): Boolean {
                                view?.let { WebViewPool.discard(it) }
                                if (!isRedirecting) {
                                    isRedirecting = true
                                    val encoded = URLEncoder.encode(url, "utf-8")
                                    scope.launch(Dispatchers.Main) {
                                        navController.navigate("OtherWebPage/$encoded") {
                                            popUpTo("ProbingPage/{url}") { inclusive = true }
                                        }
                                    }
                                }
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true && !isRedirecting) {
                                    val errorCode = error?.errorCode ?: 0
                                    val fatalErrors = listOf(
                                        ERROR_HOST_LOOKUP,
                                        ERROR_CONNECT,
                                        ERROR_BAD_URL
                                    )
                                    if (fatalErrors.contains(errorCode)) {
                                        isRedirecting = true
                                        val encoded = URLEncoder.encode(url, "utf-8")
                                        scope.launch(Dispatchers.Main) {
                                            navController.navigate("OtherWebPage/$encoded") {
                                                popUpTo("ProbingPage/{url}") { inclusive = true }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        webViewRef = this
                        val finalUrl =
                            if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"

                        resumeTimers()

                        post { loadUrl(finalUrl) }
                    }
                },
                onRelease = { webView ->
                    webViewRef = null
                    WebViewPool.release(webView)
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
            )
        }
    }
}