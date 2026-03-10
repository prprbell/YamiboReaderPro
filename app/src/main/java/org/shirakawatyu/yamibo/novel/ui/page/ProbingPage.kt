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
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProbingPage(url: String, navController: NavController) {
    SetStatusBarColor(Color.Black)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRedirecting by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = YamiboColors.primary)

            AndroidView(
                factory = {
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            blockNetworkImage = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, loadedUrl: String) {
                                if (isRedirecting) return
                                val checkJs = """
                                (function() {
                                    var sectionHeader = document.querySelector('.header h2 a');
                                    var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
                                    var currentUrl = window.location.href;
                                    var mangaSections = ['中文百合漫画区', '贴图区', '貼圖區', '原创图作区', '百合漫画图源区'];
                                    var isManga = mangaSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=30') !== -1;
                                    var novelSections = ['文學區', '文学区', '轻小说/译文区', 'TXT小说区'];
                                    var isNovel = novelSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=55') !== -1;
                                    if (isNovel) return 1;
                                    if (isManga) return 2;
                                    return 3; // 其他页面
                                })();
                            """.trimIndent()

                                view.evaluateJavascript(checkJs) { result ->
                                    val type = result?.toIntOrNull() ?: 3
                                    scope.launch(Dispatchers.IO) {
                                        FavoriteUtil.getFavoriteMap { map ->
                                            map[url]?.let { fav ->
                                                FavoriteUtil.updateFavorite(fav.copy(type = type))
                                            }
                                        }

                                        withContext(Dispatchers.Main) {
                                            if (isRedirecting) return@withContext
                                            isRedirecting = true
                                            val encoded = URLEncoder.encode(url, "utf-8")
                                            val route = when (type) {
                                                1 -> "ReaderPage/$encoded"
                                                2 -> "MangaWebPage/$encoded/$encoded"
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
                        val finalUrl =
                            if (url.startsWith("http")) url else "${RequestConfig.BASE_URL}/$url"
                        post { loadUrl(finalUrl) }
                    }
                },
                onRelease = { webView ->
                    webView.apply {
                        onPause()
                        stopLoading()
                        webViewClient = WebViewClient() 
                        setWebChromeClient(null)
                        (parent as? ViewGroup)?.removeView(this)
                        destroy()
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
            )
        }
    }
}
