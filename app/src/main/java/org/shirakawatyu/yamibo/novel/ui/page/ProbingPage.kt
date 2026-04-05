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
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
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

    val checkJs = remember {
        """
        (function() {
            var currentUrl = window.location.href;
            if (!currentUrl || currentUrl === 'about:blank') return 'WAIT';
            
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            
            var hasExplicitSection = (sectionName !== ''); 
            
            var mangaSections = ['中文百合漫画区', '贴图区', '貼圖區', '原创图作区', '百合漫画图源区'];
            var isManga = mangaSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=30') !== -1;
            
            var novelSections = ['文學區', '文学区', '轻小说/译文区', 'TXT小说区'];
            var isNovel = novelSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=55') !== -1;
            
            if (hasExplicitSection && !isManga && !isNovel) {
                return "3";
            }
            
            var isSystemPage = document.querySelector('.footer, #footer, .a_ft, #toptb');
            
            if (isSystemPage && !isManga && !isNovel) {
                return "3";
            }
            
            if (!hasExplicitSection && !isSystemPage && document.readyState !== 'complete') {
                return 'WAIT';
            }
            
            var type = 3;
            var authorId = "";
            
            if (isNovel) {
                type = 1;
                var onlyOpBtn = document.querySelector('.nav-more-item');
                if (onlyOpBtn && onlyOpBtn.href) {
                    var match = onlyOpBtn.href.match(/authorid=(\d+)/);
                    if (match) authorId = match[1];
                }
                return "1:::" + authorId;
            } else if (isManga) {
                type = 2;
                var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                
                // 漫画区特供：如果图片还没刷出来，容忍等待一下
                if (allImgs.length === 0 && document.readyState !== 'complete') return 'WAIT';
                
                var urls = [];
                for (var i = 0; i < allImgs.length; i++) {
                    var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                    if (rawSrc) urls.push(new URL(rawSrc, document.baseURI).href);
                }
                
                if (urls.length === 0 && document.readyState === 'complete') return "3";
                
                if (urls.length > 0) {
                    var encodedTitle = encodeURIComponent(document.title);
                    var encodedHtml = encodeURIComponent(document.documentElement.outerHTML);
                    return "2:::" + encodedTitle + ":::" + urls.join('|||') + ":::" + encodedHtml;
                }
                return 'WAIT';
            }
            
            return type.toString();
        })();
        """.trimIndent()
    }

    LaunchedEffect(webViewRef) {
        val webView = webViewRef ?: return@LaunchedEffect
        val checkInterval = 500L
        val maxWaitTime = 8000L // 8秒兜底
        val startTime = System.currentTimeMillis()

        while (!isRedirecting && (System.currentTimeMillis() - startTime) < maxWaitTime) {
            kotlinx.coroutines.delay(checkInterval)

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
                        FavoriteUtil.getFavoriteMap { map ->
                            map[url]?.let { fav ->
                                if (type == 1) {
                                    val authorId = parts.getOrNull(1)
                                    FavoriteUtil.updateFavorite(
                                        fav.copy(
                                            type = 1,
                                            authorId = authorId
                                        )
                                    )
                                } else {
                                    FavoriteUtil.updateFavorite(fav.copy(type = type))
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val encoded = URLEncoder.encode(url, "utf-8")
                            if (type == 2) {
                                val title = URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                                val urlsJoined = parts.getOrNull(2) ?: ""
                                val htmlContent =
                                    URLDecoder.decode(parts.getOrNull(3) ?: "", "UTF-8")
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

                            private fun handleFatalError() {
                                if (!isRedirecting) {
                                    isRedirecting = true
                                    val encoded = URLEncoder.encode(url, "utf-8")
                                    scope.launch(Dispatchers.Main) {
                                        navController.navigate("OtherWebPage/$encoded") {
                                            popUpTo("ProbingPage/{url}") { inclusive = true }
                                        }
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    val errorCode = error?.errorCode ?: 0
                                    val fatalErrors = listOf(
                                        ERROR_HOST_LOOKUP,
                                        ERROR_CONNECT,
                                        ERROR_BAD_URL,
                                        ERROR_TIMEOUT,
                                        ERROR_UNKNOWN,
                                        ERROR_FAILED_SSL_HANDSHAKE
                                    )
                                    if (fatalErrors.contains(errorCode)) {
                                        handleFatalError()
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
                                if (failingUrl == view?.url) {
                                    val fatalErrors = listOf(
                                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_BAD_URL,
                                        ERROR_TIMEOUT, ERROR_UNKNOWN, ERROR_FAILED_SSL_HANDSHAKE
                                    )
                                    if (fatalErrors.contains(errorCode)) {
                                        handleFatalError()
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
