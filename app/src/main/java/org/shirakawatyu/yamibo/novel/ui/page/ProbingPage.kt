package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.manga.MangaImagePipeline
import org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner
import java.net.URLEncoder

private val mangaSections = listOf("中文百合漫画区", "贴图区", "貼圖區", "原创图作区", "百合漫画图源区")
private val novelSections = listOf("文學區", "文学区", "轻小说/译文区", "TXT小说区")
private val mangaFids = setOf("30")
private val novelFids = setOf("55")

private val IMG_TAG_REGEX = Regex(
    """<img\s+[^>]*?(?:zsrc|data-src|file|src)=["']([^"']+)["'][^>]*>""",
    RegexOption.IGNORE_CASE
)
private val IGNORED_IMG_PATTERNS = listOf("smiley", "icon", "avatar", "勋章", "star", "thumb")

private fun isIgnoredImageUrl(url: String): Boolean {
    return IGNORED_IMG_PATTERNS.any { url.contains(it, ignoreCase = true) }
}

private fun safeConcatUrl(base: String, path: String): String {
    val rawUrl = if (path.startsWith("http")) path
    else "${base.trimEnd('/')}/${path.trimStart('/')}"

    return if (rawUrl.startsWith("http://data/") || rawUrl.startsWith("https://data/")) {
        rawUrl.replaceFirst(Regex("^https?://data/"), "${RequestConfig.BASE_URL}/data/")
    } else rawUrl
}

@Composable
fun ProbingPage(url: String, navController: NavController) {
    SetStatusBarColor(Color.Black)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRedirecting by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (isRedirecting) return@LaunchedEffect

        val tid = MangaTitleCleaner.extractTidFromUrl(url)
        if (tid == null) {
            isRedirecting = true
            val encoded = URLEncoder.encode(url, "utf-8")
            navController.navigate("OtherWebPage/$encoded") {
                popUpTo("ProbingPage/{url}") { inclusive = true }
            }
            return@LaunchedEffect
        }

        try {
            val api = YamiboRetrofit.getInstance().create(MangaApi::class.java)
            val resp = withContext(Dispatchers.IO) { api.getThreadDetailApi(tid).string() }
            val json = JSON.parseObject(resp)
            val variables = json.getJSONObject("Variables") ?: throw Exception("Missing Variables")
            val thread = variables.getJSONObject("thread") ?: throw Exception("Missing thread")
            val forumName = variables.getJSONObject("forum")?.getString("name") ?: ""
            val title = thread.getString("subject") ?: ""
            val authorId = thread.getString("authorid") ?: ""
            val fid = thread.getString("fid") ?: ""

            // 确定类型
            val type = when {
                fid in mangaFids || mangaSections.any { forumName.contains(it) } -> 2
                fid in novelFids || (forumName.isNotEmpty() && novelSections.any { forumName.contains(it) }) -> 1
                else -> 3
            }

            if (isRedirecting) return@LaunchedEffect
            isRedirecting = true

            // 更新收藏元数据
            scope.launch(Dispatchers.IO) {
                val map = FavoriteUtil.getFavoriteMapSuspend()
                map[url]?.let { fav ->
                    var changed = false
                    var newFav = fav

                    if (type == 1) {
                        val aid = authorId.takeIf { it.isNotBlank() }
                        if (fav.type != 1 || fav.authorId != aid) {
                            newFav = newFav.copy(type = 1, authorId = aid)
                            changed = true
                        }
                        var rawTitle = title
                        rawTitle = rawTitle.replace(
                            Regex("\\s+[-—–_]+\\s+.*?(文学区|小说区|译文区|百合会|论坛).*$"),
                            ""
                        ).trim()
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
                    when (type) {
                        1 -> {
                            navController.navigate("ReaderPage/$encoded") {
                                popUpTo("ProbingPage/{url}") { inclusive = true }
                            }
                        }
                        2 -> {
                            val postList = variables.getJSONArray("postlist")
                            val (urls, compatibleHtml) = extractMangaData(postList, authorId)
                            MangaImagePipeline.handoffPrefetch(
                                context = context.applicationContext,
                                urls = urls,
                                clickedIndex = 0
                            )
                            GlobalData.tempMangaUrls = urls
                            GlobalData.tempTitle = title
                            GlobalData.tempHtml = compatibleHtml
                            GlobalData.tempMangaIndex = 0

                            navController.navigate("MangaWebPage/$encoded/$encoded?fastForward=true") {
                                popUpTo("ProbingPage/{url}") { inclusive = true }
                            }
                            navController.navigate("NativeMangaPage?url=$encoded")
                        }
                        else -> {
                            navController.navigate("OtherWebPage/$encoded") {
                                popUpTo("ProbingPage/{url}") { inclusive = true }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (isRedirecting) return@LaunchedEffect
            isRedirecting = true
            val encoded = URLEncoder.encode(url, "utf-8")
            navController.navigate("OtherWebPage/$encoded") {
                popUpTo("ProbingPage/{url}") { inclusive = true }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = YamiboColors.primary)
        }
    }
}

private fun extractMangaData(
    postList: com.alibaba.fastjson2.JSONArray?,
    threadAuthorId: String
): Pair<List<String>, String> {
    if (postList == null || postList.isEmpty()) return emptyList<String>() to ""

    val urls = mutableListOf<String>()
    val seenUrls = mutableSetOf<String>()
    val combinedMessage = StringBuilder()

    for (i in 0 until postList.size) {
        val post = postList.getJSONObject(i) ?: continue
        val postAuthorId = post.getString("authorid") ?: ""

        if (i != 0 && postAuthorId != threadAuthorId) continue

        val message = post.getString("message") ?: ""
        combinedMessage.append(message).append("<br/>")

        val matches = IMG_TAG_REGEX.findAll(message)
        for (match in matches) {
            val rawUrl = match.groupValues[1]
            if (!isIgnoredImageUrl(rawUrl)) {
                val fullUrl = safeConcatUrl(RequestConfig.BASE_URL, rawUrl)
                if (seenUrls.add(fullUrl)) urls.add(fullUrl)
            }
        }

        val attachments = post.getJSONObject("attachments")
        if (attachments != null) {
            for (key in attachments.keys) {
                val attachObj = attachments.getJSONObject(key) ?: continue
                val urlPrefix = attachObj.getString("url") ?: ""
                val attachmentPath = attachObj.getString("attachment") ?: ""
                if (urlPrefix.isNotEmpty() && attachmentPath.isNotEmpty()) {
                    val fullUrl = safeConcatUrl(
                        if (urlPrefix.startsWith("http")) urlPrefix else "${RequestConfig.BASE_URL}/$urlPrefix",
                        attachmentPath
                    )
                    if (seenUrls.add(fullUrl) && !isIgnoredImageUrl(fullUrl)) {
                        urls.add(fullUrl)
                    }
                }
            }
        }
    }

    val compatibleHtml = "<div class=\"message\">$combinedMessage</div>"
    return urls.toList() to compatibleHtml
}
