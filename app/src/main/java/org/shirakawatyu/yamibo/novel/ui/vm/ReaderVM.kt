package org.shirakawatyu.yamibo.novel.ui.vm

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType
import org.shirakawatyu.yamibo.novel.bean.ReaderSettings
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.NovelApi
import org.shirakawatyu.yamibo.novel.ui.page.typefaceFromMode
import org.shirakawatyu.yamibo.novel.ui.state.ChapterInfo
import org.shirakawatyu.yamibo.novel.ui.state.ReaderState
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.reader.CacheData
import org.shirakawatyu.yamibo.novel.util.reader.CacheUtil
import org.shirakawatyu.yamibo.novel.util.reader.ChineseConvertUtil
import org.shirakawatyu.yamibo.novel.util.reader.FontMetricsUtil
import org.shirakawatyu.yamibo.novel.util.reader.HTMLUtil
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import org.shirakawatyu.yamibo.novel.util.reader.TextUtil
import org.shirakawatyu.yamibo.novel.util.reader.ValueUtil
import kotlin.math.ceil
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@SuppressLint("SetJavaScriptEnabled")
class ReaderVM(private val applicationContext: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderState())
    val uiState = _uiState.asStateFlow()
    private val _currentPercentage = MutableStateFlow(0f)
    val currentPercentage = _currentPercentage.asStateFlow()

    private var pagerState: PagerState? = null
    private var maxHeight = 0.dp
    private var maxWidth = 0.dp
    private var initialized = false
    private val logTag = "ReaderVM"
    private var compositionScope: CoroutineScope? = null
    private var pageEnterTime = 0L
    private var loadJob: Job? = null
    private var loadRequestId = 0
    private var prevHtmlList: List<Content>? = null
    private var prevChapterList: List<ChapterInfo>? = null
    var isPreloadingPrev by mutableStateOf(false)
        private set
    var url by mutableStateOf("")
        private set
    var isTransitioning by mutableStateOf(false)
    var showLoadingScrim by mutableStateOf(false)
        private set

    private val rawContentList = ArrayList<Content>()
    private var latestPage: Int = 0
    private var currentAuthorId: String? = null
        set(value) {
            field = value
            _uiState.value = _uiState.value.copy(authorId = value)
        }
    private var isPreloading = false
    private var ppp: Int = 20
    private var totalReplies: Int = 0
    private var maxPageCalculated: Int = 0
    private val PRELOAD_THRESHOLD_VERTICAL = 300
    private val PRELOAD_THRESHOLD_HORIZONTAL = 30
    private var viewBeingPreloaded = 0
    private var nextHtmlList: List<Content>? = null
    private var nextChapterList: List<ChapterInfo>? = null

    // ==================== 缓存相关 ====================
    private val diskCacheRetries = mutableMapOf<Int, Int>()
    private val MAX_CACHE_RETRIES = 2
    private val CACHE_RETRY_DELAY_MS = 5000L

    private val localCache by lazy { LocalCacheUtil.getInstance(applicationContext) }

    private val _cachedPages = MutableStateFlow<Set<Int>>(emptySet())
    val cachedPages: StateFlow<Set<Int>> = _cachedPages

    private val _cacheProgress = MutableStateFlow<CacheProgress?>(null)
    val cacheProgress: StateFlow<CacheProgress?> = _cacheProgress

    private val _isDiskCaching = MutableStateFlow(false)
    val isDiskCaching: StateFlow<Boolean> = _isDiskCaching.asStateFlow()

    private var diskCacheQueue: MutableSet<Int> = mutableSetOf()
    private var diskCacheIncludeImages: Boolean = false
    private var ignoreFirstFakeZero = false
    private var diskCacheTotalPages: Int = 0
    private var diskCacheCurrentPage: Int = 0
    private var currentRawHtml: String? = null
    private var currentCacheSessionShowsProgress: Boolean = true
    private var currentAsciiRatios: FloatArray = FloatArray(128) { 0.5f }

    data class CacheProgress(
        val totalPages: Int,
        val currentPage: Int,
        val currentPageNum: Int,
        val isComplete: Boolean = false
    )

    init {
        viewModelScope.launch {
            localCache.index.collect { index ->
                if (url.isNotEmpty()) {
                    updateCachedPagesFromIndex(index)
                }
            }
        }
    }

    private fun updateCachedPagesFromIndex(index: Map<String, LocalCacheUtil.CacheIndex>) {
        val novelCache = index[url]
        if (novelCache != null && novelCache.pages.isNotEmpty()) {
            _cachedPages.value = novelCache.pages.keys
        } else {
            _cachedPages.value = emptySet()
        }
    }

    // ==================== 磁盘缓存功能（保持不变） ====================
    fun startCaching(
        pagesToCache: Set<Int>,
        includeImages: Boolean = false,
        showProgressDialog: Boolean = true
    ) {
        if (_isDiskCaching.value) {
            Log.w(logTag, "Already caching. New request ignored.")
            return
        }
        if (pagesToCache.isEmpty()) return

        _isDiskCaching.value = true
        diskCacheQueue = pagesToCache.toMutableSet()
        diskCacheIncludeImages = includeImages
        diskCacheTotalPages = pagesToCache.size
        diskCacheCurrentPage = 0
        diskCacheRetries.clear()
        currentCacheSessionShowsProgress = showProgressDialog
        viewModelScope.launch(Dispatchers.Main) {
            loadNextPageForDiskCache(true)
        }
    }

    private fun isDiskCacheHtmlValid(htmlContent: String?): Boolean {
        if (htmlContent.isNullOrBlank()) return false
        if (htmlContent.length < 300) return false
        if (htmlContent.contains("[Error] Content element not found") ||
            htmlContent.contains("[Error] 页面加载失败")) return false
        if (!htmlContent.contains("class=\"message\"") && !htmlContent.contains("class='message'")) return false
        return true
    }

    private fun loadPageForDiskCache(pageNum: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
                val authorId = currentAuthorId ?: run {
                    withContext(Dispatchers.Main) { processDiskCachePage(pageNum, "", 1) }
                    return@launch
                }
                val resp = api.getThreadPageByAuthor(tid = extractTid(), page = pageNum, authorid = authorId)
                val json = com.alibaba.fastjson2.JSON.parseObject(resp.string())
                val variables = json.getJSONObject("Variables")
                val postlist = variables.getJSONArray("postlist")
                val messages = (0 until postlist.size).map { i ->
                    postlist.getJSONObject(i).getString("message")
                }
                val combinedHtml = messages.joinToString("") { msg -> "<div class=\"message\">$msg</div>" }

                if (maxPageCalculated == 0) {
                    val thread = variables.getJSONObject("thread")
                    totalReplies = thread.getString("replies")?.toIntOrNull() ?: 0
                    ppp = variables.getString("ppp")?.toIntOrNull() ?: 20
                    maxPageCalculated = ceil((totalReplies + 1f) / ppp).toInt().coerceAtLeast(1)
                }

                withContext(Dispatchers.Main) { processDiskCachePage(pageNum, combinedHtml, maxPageCalculated) }
            } catch (e: Exception) {
                Log.e(logTag, "DiskCache: Error loading page $pageNum", e)
                withContext(Dispatchers.Main) { processDiskCachePage(pageNum, "", 1) }
            }
        }
    }

    private suspend fun processDiskCachePage(pageNum: Int, html: String, maxPage: Int) {
        if (!_isDiskCaching.value || !diskCacheQueue.contains(pageNum)) return
        if (!isDiskCacheHtmlValid(html)) {
            val currentRetries = diskCacheRetries.getOrDefault(pageNum, 0)
            if (currentRetries < MAX_CACHE_RETRIES) {
                diskCacheRetries[pageNum] = currentRetries + 1
                Log.w(logTag, "DiskCache: Retry $pageNum (${currentRetries + 1}/${MAX_CACHE_RETRIES + 1})")
                delay(CACHE_RETRY_DELAY_MS)
                if (_isDiskCaching.value && diskCacheQueue.contains(pageNum)) {
                    loadPageForDiskCache(pageNum)
                }
            } else {
                Log.e(logTag, "DiskCache: Give up $pageNum after max retries")
                diskCacheRetries.remove(pageNum)
                _cachedPages.value -= pageNum
                diskCacheQueue.remove(pageNum)
                loadNextPageForDiskCache(false)
            }
            return
        }
        diskCacheRetries.remove(pageNum)

        val cacheData = CacheData(cachedPageNum = pageNum, htmlContent = html, maxPageNum = maxPage, authorId = currentAuthorId)
        withContext(Dispatchers.IO) { localCache.savePage(url, pageNum, cacheData, diskCacheIncludeImages) }
        CacheUtil.saveCache(url, cacheData)

        withContext(Dispatchers.Main) {
            _cachedPages.value += pageNum
            diskCacheQueue.remove(pageNum)
            loadNextPageForDiskCache(false)
        }
    }

    private fun loadNextPageForDiskCache(showInitialProgress: Boolean = true) {
        if (!_isDiskCaching.value || diskCacheQueue.isEmpty()) {
            _isDiskCaching.value = false
            if (currentCacheSessionShowsProgress && _cacheProgress.value != null && _cacheProgress.value?.isComplete == false) {
                _cacheProgress.value = _cacheProgress.value?.copy(currentPage = diskCacheTotalPages, isComplete = true)
            }
            return
        }
        val pageNum = diskCacheQueue.first()
        diskCacheCurrentPage++
        if (currentCacheSessionShowsProgress) {
            val shouldShow = if (diskCacheCurrentPage == 1) showInitialProgress else true
            if (shouldShow) {
                _cacheProgress.value = CacheProgress(
                    totalPages = diskCacheTotalPages,
                    currentPage = diskCacheCurrentPage,
                    currentPageNum = pageNum
                )
            }
        }

        CacheUtil.getCache(url, pageNum) { memoryCacheData ->
            if (memoryCacheData != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    localCache.savePage(url, pageNum, memoryCacheData, diskCacheIncludeImages)
                    withContext(Dispatchers.Main) {
                        _cachedPages.value += pageNum
                        diskCacheQueue.remove(pageNum)
                        loadNextPageForDiskCache(false)
                    }
                }
            } else {
                loadPageForDiskCache(pageNum)
            }
        }
    }

    fun deleteCachedPages(pagesToDelete: Set<Int>) {
        viewModelScope.launch {
            try {
                pagesToDelete.forEach { pageNum -> localCache.deletePage(url, pageNum) }
                _cachedPages.value -= pagesToDelete
            } catch (e: Exception) {
                Log.e(logTag, "Failed to delete cached pages", e)
            }
        }
    }

    fun updateCachedPages(pagesToUpdate: Set<Int>, includeImages: Boolean, showProgressDialog: Boolean = true) {
        viewModelScope.launch {
            try {
                pagesToUpdate.forEach { pageNum -> localCache.deletePage(url, pageNum) }
                _cachedPages.value -= pagesToUpdate
                startCaching(pagesToUpdate, includeImages, showProgressDialog)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to update cached pages", e)
            }
        }
    }

    fun resetCacheProgress() {
        _cacheProgress.value = null
        currentCacheSessionShowsProgress = false
    }

    fun showCacheProgress() {
        if (_isDiskCaching.value && _cacheProgress.value == null) {
            currentCacheSessionShowsProgress = true
            _cacheProgress.value = CacheProgress(
                totalPages = diskCacheTotalPages,
                currentPage = diskCacheCurrentPage,
                currentPageNum = diskCacheQueue.firstOrNull() ?: 0,
                isComplete = false
            )
        }
    }

    fun stopCaching() {
        if (!_isDiskCaching.value) return
        _isDiskCaching.value = false
        diskCacheQueue.clear()
        _cacheProgress.value = null
        diskCacheTotalPages = 0
        diskCacheCurrentPage = 0
    }

    // ==================== 阅读核心逻辑 ====================

    fun firstLoad(initUrl: String, initHeight: Dp, initWidth: Dp) {
        pageEnterTime = System.currentTimeMillis()
        viewModelScope.launch {
            val pageMatch = Regex("(?<=[?&])page=(\\d+)").find(initUrl)
            val initialPageNum = pageMatch?.groupValues?.get(1)?.toIntOrNull()

            val authorIdMatch = Regex("(?<=[?&])authorid=(\\d+)").find(initUrl)
            if (authorIdMatch != null && currentAuthorId == null) {
                currentAuthorId = authorIdMatch.groupValues[1]
            }

            var cleanUrl = initUrl.replace(Regex("(?<=[?&])page=\\d+&?"), "")
            cleanUrl = cleanUrl.replace(Regex("(?<=[?&])authorid=\\d+&?"), "")
            cleanUrl = cleanUrl.removeSuffix("&").removeSuffix("?")
            url = cleanUrl
            maxWidth = initWidth
            maxHeight = initHeight

            updateCachedPagesFromIndex(localCache.index.value)

            val applySettingsAndLoad = { settings: ReaderSettings? ->
                val bgColor = settings?.backgroundColor?.let {
                    try { Color(it.toColorInt()) } catch (_: Exception) { null }
                }
                _uiState.value = _uiState.value.copy(
                    fontSize = settings?.fontSizePx?.let { ValueUtil.pxToSp(it) } ?: 24.sp,
                    lineHeight = settings?.lineHeightPx?.let { ValueUtil.pxToSp(it) } ?: 43.sp,
                    padding = (settings?.paddingDp ?: 16f).dp,
                    nightMode = settings?.nightMode ?: false,
                    backgroundColor = bgColor,
                    loadImages = settings?.loadImages ?: false,
                    isVerticalMode = settings?.isVerticalMode ?: false,
                    translationMode = settings?.translationMode ?: 0,
                    fontFamily = settings?.getFontFamily() ?: 0
                )
                updateFontRatios()
                loadWithSettings(initialPageNum)
            }

            SettingsUtil.getSettings(
                callback = { settings -> applySettingsAndLoad(settings) },
                onNull = { applySettingsAndLoad(null) }
            )
            viewModelScope.launch {
                FavoriteUtil.getFavoriteFlow().collect { favorites ->
                    val isFavorited = favorites.any { it.url == url }
                    _uiState.value = _uiState.value.copy(isFavorited = isFavorited)
                }
            }
        }
    }

    private fun loadWithSettings(initialPageNum: Int? = null) {
        viewModelScope.launch {
            if (!initialized) showLoadingScrim = true
            val favMap = FavoriteUtil.getFavoriteMapSuspend()
            val favorite = favMap[url]
            val targetView = favorite?.lastView ?: initialPageNum ?: 1
            if (favorite?.authorId != null) currentAuthorId = favorite.authorId

            val targetIndex: Int = if (_uiState.value.isVerticalMode) {
                val avgItemsPerPage = getAvgItemsPerHorizontalPage()
                ((favorite?.lastPage ?: 0) * avgItemsPerPage)
            } else {
                favorite?.lastPage ?: 0
            }

            // 检查本地缓存
            val localData = withContext(Dispatchers.IO) { localCache.loadPage(url, targetView) }
            if (localData != null) {
                if (currentAuthorId == null && localData.authorId != null) currentAuthorId = localData.authorId
                _uiState.value = _uiState.value.copy(currentView = targetView, maxWebView = localData.maxPageNum)
                loadFinished(success = true, html = localData.htmlContent, loadedUrl = null,
                    maxPage = localData.maxPageNum, isFromCache = true, cacheTargetIndex = targetIndex,
                    targetView = targetView)
                return@launch
            }

            // 内存缓存
            val memData = suspendCoroutine<CacheData?> { cont ->
                CacheUtil.getCache(url, targetView) { cont.resume(it) }
            }
            if (memData != null && memData.authorId == currentAuthorId) {
                if (currentAuthorId == null && memData.authorId != null) currentAuthorId = memData.authorId
                _uiState.value = _uiState.value.copy(currentView = targetView, maxWebView = memData.maxPageNum)
                loadFinished(success = true, html = memData.htmlContent, loadedUrl = null,
                    maxPage = memData.maxPageNum, isFromCache = true, cacheTargetIndex = targetIndex,
                    targetView = targetView)
                return@launch
            }

            // 网络加载
            _uiState.value = _uiState.value.copy(currentView = targetView, initPage = targetIndex)
            startNetworkLoad(targetView)
        }
    }

    // ==================== 页面切换 (onSetView) ====================
    fun onSetView(view: Int, forceReload: Boolean = false) {
        if (view == _uiState.value.currentView && !isTransitioning && !forceReload) return

        loadJob?.cancel()
        loadRequestId++
        val thisRequestId = loadRequestId

        // 预加载命中
        if (view == _uiState.value.currentView + 1 && nextHtmlList != null && !forceReload) {
            isTransitioning = true
            _uiState.value = _uiState.value.copy(
                htmlList = nextHtmlList!!,
                chapterList = nextChapterList ?: listOf(),
                initPage = 0,
                currentView = view
            )
            _currentPercentage.value = 0f
            nextHtmlList = null
            nextChapterList = null
            latestPage = 0
            return
        }

        // 清理预加载状态
        nextHtmlList = null
        nextChapterList = null
        isPreloading = false

        if (forceReload) CacheUtil.clearCacheEntry(url, view)

        // 立即更新 currentView 并显示遮罩
        _uiState.value = _uiState.value.copy(currentView = view, isError = false)
        _currentPercentage.value = 0f
        isTransitioning = true
        showLoadingScrim = true

        loadJob = viewModelScope.launch(Dispatchers.Main) {
            // 优先尝试缓存
            val localData = withContext(Dispatchers.IO) { localCache.loadPage(url, view) }
            if (thisRequestId != loadRequestId) return@launch
            if (localData != null && localData.authorId == currentAuthorId) {
                _uiState.value = _uiState.value.copy(initPage = 0, maxWebView = localData.maxPageNum)
                loadFinished(success = true, html = localData.htmlContent, loadedUrl = null,
                    maxPage = localData.maxPageNum, isFromCache = true, cacheTargetIndex = 0,
                    targetView = view)
                return@launch
            }

            val memData = suspendCoroutine<CacheData?> { cont ->
                CacheUtil.getCache(url, view) { cont.resume(it) }
            }
            if (thisRequestId != loadRequestId) return@launch
            if (memData != null && memData.authorId == currentAuthorId) {
                _uiState.value = _uiState.value.copy(initPage = 0, maxWebView = memData.maxPageNum)
                loadFinished(success = true, html = memData.htmlContent, loadedUrl = null,
                    maxPage = memData.maxPageNum, isFromCache = true, cacheTargetIndex = 0,
                    targetView = view)
                return@launch
            }

            // 网络加载
            if (thisRequestId != loadRequestId) return@launch
            try {
                val (html, maxPage, title) = loadFromApi(view)
                if (thisRequestId != loadRequestId) return@launch
                loadFinished(success = true, html = html, loadedUrl = null, maxPage = maxPage,
                    title = title, isFromCache = false, targetView = view)
            } catch (e: CancellationException) {
                // 协程被取消，不处理
            } catch (e: Exception) {
                if (thisRequestId != loadRequestId) return@launch
                loadFinished(success = false, html = "", loadedUrl = null, maxPage = 1,
                    isFromCache = false, targetView = view)
            }
        }
    }

    private suspend fun loadFromApi(view: Int): Triple<String, Int, String?> {
        val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
        // 获取 authorId（如果还没有）
        if (currentAuthorId == null) {
            val metaResp = api.getThreadFirstPage(tid = extractTid(), page = 1)
            val metaJson = com.alibaba.fastjson2.JSON.parseObject(metaResp.string())
            val authorId = metaJson.getJSONObject("Variables").getJSONObject("thread").getString("authorid")
            if (authorId.isNullOrEmpty()) throw Exception("无法获取作者ID")
            currentAuthorId = authorId
            viewModelScope.launch(Dispatchers.IO) {
                val map = FavoriteUtil.getFavoriteMapSuspend()
                map[url]?.let { FavoriteUtil.updateFavoriteSuspend(it.copy(authorId = authorId)) }
            }
        }
        val authorId = currentAuthorId!!
        val resp = api.getThreadPageByAuthor(tid = extractTid(), page = view, authorid = authorId)
        val json = com.alibaba.fastjson2.JSON.parseObject(resp.string())
        val variables = json.getJSONObject("Variables")
        val thread = variables.getJSONObject("thread")

        if (maxPageCalculated == 0) {
            ppp = variables.getString("ppp")?.toIntOrNull() ?: 20
            totalReplies = thread.getString("replies")?.toIntOrNull() ?: 0
            maxPageCalculated = ceil((totalReplies + 1f) / ppp).toInt().coerceAtLeast(1)
        }

        val postlist = variables.getJSONArray("postlist")
        val messages = (0 until postlist.size).map { i -> postlist.getJSONObject(i).getString("message") }
        val combinedHtml = messages.joinToString("") { msg -> "<div class=\"message\">$msg</div>" }

        CacheUtil.saveCache(url, CacheData(cachedPageNum = view, htmlContent = combinedHtml, maxPageNum = maxPageCalculated, authorId = authorId))
        val title = thread.getString("subject")
        return Triple(combinedHtml, maxPageCalculated, title)
    }

    fun loadFinished(
        success: Boolean,
        html: String,
        loadedUrl: String?,
        maxPage: Int,
        title: String? = null,
        isFromCache: Boolean = false,
        cacheTargetIndex: Int = 0,
        targetView: Int = _uiState.value.currentView
    ) {
        if (success) currentRawHtml = html

        viewModelScope.launch {
            if (!initialized) {
                val elapsed = System.currentTimeMillis() - pageEnterTime
                if (elapsed < 350L) delay(350L - elapsed)
            }

            if (!success) {
                _uiState.value = _uiState.value.copy(isError = true, htmlList = emptyList(), maxWebView = maxPage)
                showLoadingScrim = false
                isTransitioning = false
                return@launch
            }

            if (!isFromCache) {
                _uiState.value = _uiState.value.copy(maxWebView = maxPage)
                FavoriteUtil.checkAndUpdateTitleSuspend(url, title)
            }

            val (passages, chapters) = withContext(Dispatchers.Default) {
                parseHtmlToContent(html)
                paginateContent(isFromCache, targetView)
            }

            if (isPreloading) {
                if (targetView != viewBeingPreloaded) {
                    Log.w(logTag, "Preload mismatch: expected $viewBeingPreloaded, got $targetView")
                    return@launch
                }
                isPreloading = false
                val cacheData = CacheData(cachedPageNum = targetView, htmlContent = html, maxPageNum = maxPage, authorId = currentAuthorId)
                CacheUtil.saveCache(url, cacheData)
                if (_cachedPages.value.contains(targetView)) {
                    launch(Dispatchers.IO) { localCache.savePage(url, targetView, cacheData, false) }
                }
                nextHtmlList = passages
                nextChapterList = chapters
                val currentList = _uiState.value.htmlList
                if (currentList.isNotEmpty()) {
                    val modified = currentList.dropLast(1).toMutableList().also {
                        it.add(Content("...下一页 (网页)", ContentType.TEXT, "footer"))
                    }
                    _uiState.value = _uiState.value.copy(htmlList = modified)
                }
            } else {
                // 正常加载
                if (!isFromCache) {
                    val cacheData = CacheData(cachedPageNum = targetView, htmlContent = html, maxPageNum = maxPage, authorId = currentAuthorId)
                    CacheUtil.saveCache(url, cacheData)
                    if (_cachedPages.value.contains(targetView)) {
                        launch(Dispatchers.IO) { localCache.savePage(url, targetView, cacheData, false) }
                    }
                }

                val newInitPage = if (isFromCache) cacheTargetIndex else if (initialized) 0 else _uiState.value.initPage
                val totalItems = passages.size.coerceAtLeast(1)
                val safeInitPage = newInitPage.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                val newPercent = (safeInitPage.toFloat() / totalItems) * 100f

                ignoreFirstFakeZero = safeInitPage > 0
                _uiState.value = _uiState.value.copy(
                    htmlList = passages,
                    chapterList = chapters,
                    initPage = safeInitPage,
                    maxWebView = maxPage,
                    isError = false
                )
                _currentPercentage.value = newPercent
                if (!initialized) initialized = true
                latestPage = safeInitPage
                showLoadingScrim = false
                isTransitioning = false
            }
        }
    }

    fun retryLoad() {
        loadJob?.cancel()
        showLoadingScrim = true
        _uiState.value = _uiState.value.copy(isError = false)
        startNetworkLoad(_uiState.value.currentView)
    }

    fun forceRefreshCurrentPage() {
        val pageToRefresh = _uiState.value.currentView
        if (url.isBlank()) return
        viewModelScope.launch {
            nextHtmlList = null
            nextChapterList = null
            isPreloading = false
            showLoadingScrim = true
            _uiState.value = _uiState.value.copy(isError = false)
            loadJob?.cancel()
            loadJob = viewModelScope.launch {
                delay(10)
                try {
                    val (html, maxPage, title) = loadFromApi(pageToRefresh)
                    loadFinished(true, html, null, maxPage, title, targetView = pageToRefresh)
                } catch (e: Exception) {
                    loadFinished(false, "", null, 1, targetView = pageToRefresh)
                }
            }
        }
    }

    private fun startNetworkLoad(view: Int) {
        loadJob?.cancel()
        loadRequestId++
        val thisRequestId = loadRequestId
        showLoadingScrim = true

        loadJob = viewModelScope.launch {
            try {
                val (html, maxPage, title) = loadFromApi(view)
                if (thisRequestId != loadRequestId) return@launch
                loadFinished(success = true, html = html, loadedUrl = null, maxPage = maxPage,
                    title = title, targetView = view)
            } catch (e: CancellationException) {
                // 忽略
            } catch (e: Exception) {
                if (thisRequestId != loadRequestId) return@launch
                loadFinished(success = false, html = "", loadedUrl = null, maxPage = 1, targetView = view)
            }
        }
    }
    private fun triggerPreloadPrev(targetView: Int) {
        if (isPreloadingPrev || isPreloading || targetView < 1 || _isDiskCaching.value) return
        isPreloadingPrev = true
        viewModelScope.launch {
            try {
                val authorId = currentAuthorId ?: return@launch
                val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
                val resp = api.getThreadPageByAuthor(tid = extractTid(), page = targetView, authorid = authorId)
                val json = com.alibaba.fastjson2.JSON.parseObject(resp.string())
                val postlist = json.getJSONObject("Variables").getJSONArray("postlist")
                val messages = (0 until postlist.size).map { i -> postlist.getJSONObject(i).getString("message") }
                val combinedHtml = messages.joinToString("") { msg -> "<div class=\"message\">$msg</div>" }

                CacheUtil.saveCache(url, CacheData(cachedPageNum = targetView, htmlContent = combinedHtml, maxPageNum = maxPageCalculated, authorId = authorId))

                parseHtmlToContent(combinedHtml)
                val (passages, chapters) = paginateContent(isFromCache = false, targetWebPage = targetView)

                if (_isDiskCaching.value) return@launch // 缓存任务优先级更高，丢弃这次预加载
                prevHtmlList = passages
                prevChapterList = chapters
            } catch (_: Exception) {
                // 预加载失败不影响阅读
            } finally {
                isPreloadingPrev = false
            }
        }
    }
    fun applyPrevContent() {
        val state = _uiState.value
        val prevPages = prevHtmlList
        val prevChapters = prevChapterList
        if (prevPages == null || prevChapters == null) return
        if (state.currentView <= 1) return
        val oldList = state.htmlList
        val combinedList = prevPages + oldList
        val overallOffset = prevPages.size
        val combinedChapters = prevChapters.map { it.copy(startIndex = it.startIndex) } +
                state.chapterList.map { it.copy(startIndex = it.startIndex + overallOffset) }
        val newView = state.currentView - 1
        _uiState.value = state.copy(
            htmlList = combinedList,
            chapterList = combinedChapters,
            currentView = newView,
            initPage = overallOffset,
            maxWebView = state.maxWebView
        )
        prevHtmlList = null
        prevChapterList = null
        isPreloadingPrev = false
        saveHistory(overallOffset)
    }
    private fun triggerPreload(targetView: Int, maxView: Int) {
        if (isPreloading || targetView > maxView || _isDiskCaching.value) return
        isPreloading = true
        viewBeingPreloaded = targetView
        viewModelScope.launch {
            try {
                val authorId = currentAuthorId ?: return@launch
                val api = YamiboRetrofit.getInstance().create(NovelApi::class.java)
                val resp = api.getThreadPageByAuthor(tid = extractTid(), page = targetView, authorid = authorId)
                val json = com.alibaba.fastjson2.JSON.parseObject(resp.string())
                val postlist = json.getJSONObject("Variables").getJSONArray("postlist")
                val messages = (0 until postlist.size).map { i -> postlist.getJSONObject(i).getString("message") }
                val combinedHtml = messages.joinToString("") { msg -> "<div class=\"message\">$msg</div>" }

                CacheUtil.saveCache(url, CacheData(cachedPageNum = targetView, htmlContent = combinedHtml, maxPageNum = maxPageCalculated, authorId = authorId))

                parseHtmlToContent(combinedHtml)
                val (passages, chapters) = paginateContent(isFromCache = false, targetWebPage = targetView)

                nextHtmlList = passages
                nextChapterList = chapters

                val currentList = _uiState.value.htmlList
                if (currentList.isNotEmpty()) {
                    val modified = currentList.dropLast(1).toMutableList().also {
                        it.add(Content("...下一页 (网页)", ContentType.TEXT, "footer"))
                    }
                    _uiState.value = _uiState.value.copy(htmlList = modified)
                }
            } catch (_: Exception) {
            } finally {
                isPreloading = false
            }
        }
    }

    private fun extractTid(): String {
        return url.substringAfter("tid=").substringBefore("&")
    }

    private fun parseHtmlToContent(html: String) {
        rawContentList.clear()
        val doc = Jsoup.parse(html)
        doc.getElementsByTag("i").forEach { it.remove() }

        val messageNodes = doc.getElementsByClass("message")
        if (messageNodes.isEmpty()) return

        val rawTexts = messageNodes.map { HTMLUtil.toText(it.html()) }
        val delimiter = "|||YAMIBO_SEP|||"
        val combinedText = rawTexts.joinToString(delimiter)

        val convertedCombinedText = if (combinedText.isNotBlank()) {
            when (_uiState.value.translationMode) {
                1 -> ChineseConvertUtil.toSimplified(combinedText, applicationContext)
                2 -> ChineseConvertUtil.toTraditional(combinedText, applicationContext)
                else -> combinedText
            }
        } else {
            combinedText
        }

        val convertedTexts = convertedCombinedText.split(delimiter)
        val replyRegex = Regex("发表于\\s*\\d{4}-\\d{1,2}-\\d{1,2}")
        var currentValidTitle: String? = null

        for (i in messageNodes.indices) {
            val node = messageNodes[i]
            val rawText = convertedTexts.getOrElse(i) { rawTexts[i] }
            val firstLine = rawText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

            if (firstLine.contains(replyRegex)) continue
            currentValidTitle = firstLine.take(30)

            if (rawText.isNotBlank()) {
                rawContentList.add(Content(rawText, ContentType.TEXT, currentValidTitle))
            }

            if (_uiState.value.loadImages) {
                for (element in node.getElementsByTag("img")) {
                    var src = element.attr("zoomfile")
                    if (src.isBlank()) src = element.attr("file")
                    if (src.isBlank()) src = element.attr("src")
                    if (src.isBlank() || src.contains("smiley/")) continue

                    if (!src.startsWith("http://") && !src.startsWith("https://")) {
                        rawContentList.add(Content("${RequestConfig.BASE_URL}/${src}", ContentType.IMG, currentValidTitle))
                    } else {
                        rawContentList.add(Content(src, ContentType.IMG, currentValidTitle))
                    }
                }
            }
        }
    }

    private fun paginateContent(
        isFromCache: Boolean = false,
        targetWebPage: Int? = null
    ): Pair<List<Content>, List<ChapterInfo>> {
        updateFontRatios()
        val contentSnapshot = rawContentList.toList()
        val state = _uiState.value
        val webPageNum = targetWebPage ?: state.currentView
        val passages: List<Content>

        if (state.isVerticalMode) {
            val pageContentWidth = maxWidth - (state.padding + state.padding)
            val lines = TextUtil.pagingTextVertical(
                rawContentList = contentSnapshot,
                width = pageContentWidth,
                fontSize = state.fontSize,
                letterSpacing = state.letterSpacing,
                charRatios = currentAsciiRatios,
                typeface = typefaceFromMode(state.fontFamily)
            ).toMutableList()

            if (nextHtmlList != null && nextHtmlList!!.isNotEmpty()) {
                lines.add(nextHtmlList!!.first())
            } else if (webPageNum < state.maxWebView) {
                lines.add(Content("正在加载...", ContentType.TEXT, "footer"))
            } else {
                lines.add(Content("刷新本页内容", ContentType.TEXT, "footer"))
            }
            passages = lines
        } else {
            val passagesList = ArrayList<Content>()
            val topPadding = 24.dp
            val footerHeight = 87.dp
            val pageContentHeight = maxHeight - topPadding - footerHeight
            val pageContentWidth = maxWidth - (state.padding + state.padding)

            for (content in contentSnapshot) {
                if (content.type == ContentType.TEXT) {
                    val pagedText = TextUtil.pagingText(
                        content.data,
                        pageContentHeight,
                        pageContentWidth,
                        state.fontSize,
                        state.letterSpacing,
                        state.lineHeight,
                        currentAsciiRatios,
                        typefaceFromMode(state.fontFamily)
                    )
                    for (t in pagedText) {
                        passagesList.add(Content(t, ContentType.TEXT, content.chapterTitle))
                    }
                } else if (content.type == ContentType.IMG) {
                    passagesList.add(content)
                }
            }

            if (nextHtmlList != null && nextHtmlList!!.isNotEmpty()) {
                passagesList.add(nextHtmlList!!.first())
            } else if (webPageNum < state.maxWebView) {
                passagesList.add(Content("正在加载...", ContentType.TEXT, "footer"))
            } else {
                passagesList.add(Content("刷新本页内容", ContentType.TEXT, "footer"))
            }
            passages = passagesList
        }

        val chapterList = mutableListOf<ChapterInfo>()
        var lastTitle: String? = null
        passages.forEachIndexed { index, content ->
            if (content.chapterTitle != null && content.chapterTitle != lastTitle && content.chapterTitle != "footer") {
                chapterList.add(ChapterInfo(title = content.chapterTitle, startIndex = index))
                lastTitle = content.chapterTitle
            }
        }

        return Pair(passages, chapterList)
    }

    private fun processPageChange(newPage: Int) {
        val oldPage = latestPage
        val state = _uiState.value
        val list = state.htmlList
        val preloadPrevThreshold = if (state.isVerticalMode) 5 else 1
        val isNearTop = newPage <= preloadPrevThreshold
        if (isNearTop && !isPreloadingPrev && prevHtmlList == null &&
            state.currentView > 1 && !isTransitioning && !_isDiskCaching.value) {
            triggerPreloadPrev(state.currentView - 1)
        }
        if (list.isNotEmpty() && newPage < list.size && oldPage >= 0 && oldPage < list.size) {
            val oldChapter = list[oldPage].chapterTitle
            val newChapter = list[newPage].chapterTitle
            if (newChapter != null && newChapter != oldChapter) {
                saveHistory(newPage)
            } else if (!state.isVerticalMode) {
                if (!isTransitioning) saveHistory(newPage)
            }
        }
        val listSize = list.size
        if (listSize > 0 && newPage == listSize - 1 && nextHtmlList != null && !isTransitioning) {
            isTransitioning = true
            val newCurrentView = state.currentView + 1
            _uiState.value = state.copy(
                htmlList = nextHtmlList!!,
                chapterList = nextChapterList ?: listOf(),
                initPage = 0,
                currentView = newCurrentView
            )
            _currentPercentage.value = 0f
            nextHtmlList = null
            nextChapterList = null
            latestPage = 0
            return
        }
        val lastContentPageIndex = (listSize - 2).coerceAtLeast(0)
        val threshold = if (state.isVerticalMode) PRELOAD_THRESHOLD_VERTICAL else PRELOAD_THRESHOLD_HORIZONTAL
        val triggerPageIndex = (lastContentPageIndex - threshold).coerceAtLeast(0)
        if (listSize > 0 && !isPreloading && nextHtmlList == null &&
            state.currentView < state.maxWebView && !isTransitioning && newPage >= triggerPageIndex) {
            triggerPreload(state.currentView + 1, state.maxWebView)
        }
        latestPage = newPage
    }

    fun onPageChange(curPagerState: PagerState, scope: CoroutineScope) {
        if (pagerState == null) pagerState = curPagerState
        if (compositionScope == null) compositionScope = scope

        val newPage = curPagerState.targetPage
        if (ignoreFirstFakeZero) {
            if (newPage == 0) return
            ignoreFirstFakeZero = false
        }
        if (isTransitioning) {
            val isSettledAtInit = curPagerState.settledPage == _uiState.value.initPage && newPage == _uiState.value.initPage
            val userInterrupted = !curPagerState.isScrollInProgress &&
                    curPagerState.settledPage != _uiState.value.initPage &&
                    curPagerState.settledPage == newPage
            if (isSettledAtInit) {
                isTransitioning = false
                latestPage = _uiState.value.initPage
            } else if (userInterrupted) {
                isTransitioning = false
                latestPage = newPage
            } else {
                return
            }
        }

        val list = _uiState.value.htmlList
        if (list.isEmpty() || newPage >= list.size || newPage == latestPage) return

        val totalPages = curPagerState.pageCount.coerceAtLeast(1)
        val percent = (newPage.toFloat() / totalPages) * 100f
        _currentPercentage.value = percent

        processPageChange(newPage)
    }

    fun onVerticalPageSettled(newPage: Int) {
        if (ignoreFirstFakeZero) {
            if (newPage == 0) return
            ignoreFirstFakeZero = false
        }
        if (isTransitioning) {
            isTransitioning = false
            latestPage = newPage
        }
        if (newPage == latestPage) return

        val totalRows = _uiState.value.htmlList.size.coerceAtLeast(1)
        val percent = (newPage.toFloat() / totalRows) * 100f
        _currentPercentage.value = percent

        processPageChange(newPage)
    }

    private fun saveHistory(pageToSave: Int) {
        val currentList = _uiState.value.htmlList
        var currentChapter: String? = null
        if (pageToSave in 0 until currentList.size) {
            currentChapter = currentList[pageToSave].chapterTitle
        }
        val state = _uiState.value
        val valueToSave: Int = if (state.isVerticalMode) {
            val avgItemsPerPage = getAvgItemsPerHorizontalPage()
            (pageToSave.toFloat() / avgItemsPerPage.toFloat()).toInt()
        } else {
            pageToSave
        }

        viewModelScope.launch(Dispatchers.IO) {
            val map = FavoriteUtil.getFavoriteMapSuspend()
            map[url]?.let { fav ->
                FavoriteUtil.updateFavoriteSuspend(
                    fav.copy(
                        lastPage = valueToSave,
                        lastView = uiState.value.currentView,
                        lastChapter = currentChapter,
                        authorId = this@ReaderVM.currentAuthorId
                    )
                )
            }
        }
    }

    private fun getAvgItemsPerHorizontalPage(): Int {
        val state = _uiState.value
        val topPadding = 24.dp
        val footerHeight = 87.dp
        val pageContentHeight = maxHeight - topPadding - footerHeight
        val pageContentHeightPx = ValueUtil.dpToPx(pageContentHeight)
        val lineHeightPx = ValueUtil.spToPx(state.lineHeight)
        return (pageContentHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
    }

    private fun saveCurrentSettings() {
        val state = _uiState.value
        val backgroundColorString = state.backgroundColor?.let { String.format("#%08X", it.toArgb()) }
        val settings = ReaderSettings(
            ValueUtil.spToPx(state.fontSize),
            ValueUtil.spToPx(state.lineHeight),
            state.padding.value,
            state.nightMode,
            backgroundColorString,
            state.loadImages,
            state.isVerticalMode,
            state.translationMode,
            state.fontFamily
        )
        SettingsUtil.saveSettings(settings)
    }

    fun saveSettings(currentPage: Int) {
        saveCurrentSettings()
        viewModelScope.launch {
            showLoadingScrim = true

            val realPageCount = _uiState.value.htmlList.size
            val oldPageCount = realPageCount.coerceAtLeast(1)
            val oldPercent = currentPage.toFloat() / oldPageCount

            val (oldChapterTitle, oldItemInChapter) = if (currentPage in 0..<realPageCount) {
                val oldChapterTitle = _uiState.value.htmlList[currentPage].chapterTitle
                val oldChapterStartIndex = _uiState.value.chapterList.find { it.title == oldChapterTitle }?.startIndex ?: 0
                val oldItemInChapter = (currentPage - oldChapterStartIndex).coerceAtLeast(0)
                Pair(oldChapterTitle, oldItemInChapter)
            } else Pair(null, 0)

            val (newPages, newChapters) = withContext(Dispatchers.Default) {
                paginateContent()
            }

            val newPageCount = newPages.size.coerceAtLeast(1)
            val pageToScrollTo = if (oldChapterTitle != null) {
                val newChapterStartIndex = newChapters.find { it.title == oldChapterTitle }?.startIndex ?: 0
                (newChapterStartIndex + oldItemInChapter).coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
            } else {
                (oldPercent * newPageCount).toInt().coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
            }

            val newPercent = (pageToScrollTo.toFloat() / newPageCount) * 100f
            _uiState.value = _uiState.value.copy(
                htmlList = newPages,
                chapterList = newChapters,
                initPage = pageToScrollTo,
                isError = false
            )
            _currentPercentage.value = newPercent
            showLoadingScrim = false
            isTransitioning = false
        }
    }

    fun setReadingMode(isVertical: Boolean, currentPage: Int) {
        if (isVertical == _uiState.value.isVerticalMode) return
        _uiState.value = _uiState.value.copy(isVerticalMode = isVertical, initPage = currentPage)
        saveSettings(currentPage)
    }

    fun toggleChapterDrawer(show: Boolean) {
        _uiState.value = _uiState.value.copy(showChapterDrawer = show)
    }

    fun onSetFontSize(fontSize: TextUnit) {
        val newMinLineHeight = (fontSize.value * 1.5f).sp
        val currentLineHeight = _uiState.value.lineHeight
        if (currentLineHeight < newMinLineHeight) {
            _uiState.value = _uiState.value.copy(fontSize = fontSize, lineHeight = newMinLineHeight)
        } else {
            _uiState.value = _uiState.value.copy(fontSize = fontSize)
        }
    }

    fun onSetLineHeight(lineHeight: TextUnit) {
        val currentFontSizeValue = _uiState.value.fontSize.value
        val newMinLineHeightValue = currentFontSizeValue * 1.5f
        val coercedLineHeightValue = lineHeight.value.coerceIn(minimumValue = newMinLineHeightValue, maximumValue = 100.0f)
        _uiState.value = _uiState.value.copy(lineHeight = coercedLineHeightValue.sp)
    }

    fun onSetPadding(padding: Dp) {
        _uiState.value = _uiState.value.copy(padding = padding)
    }

    fun toggleNightMode(isNight: Boolean) {
        _uiState.value = _uiState.value.copy(nightMode = isNight)
        saveCurrentSettings()
    }

    fun toggleLoadImages(load: Boolean) {
        if (_uiState.value.loadImages == load) return
        _uiState.value = _uiState.value.copy(loadImages = load)
        saveCurrentSettings()

        val currentPage = latestPage
        val html = currentRawHtml
        if (html != null) {
            viewModelScope.launch {
                showLoadingScrim = true
                val realPageCount = _uiState.value.htmlList.size
                val oldPageCount = realPageCount.coerceAtLeast(1)
                val oldPercent = currentPage.toFloat() / oldPageCount
                val (oldChapterTitle, oldItemInChapter) = if (currentPage in 0..<realPageCount) {
                    val title = _uiState.value.htmlList[currentPage].chapterTitle
                    val startIndex = _uiState.value.chapterList.find { it.title == title }?.startIndex ?: 0
                    Pair(title, (currentPage - startIndex).coerceAtLeast(0))
                } else Pair(null, 0)

                val (newPages, newChapters) = withContext(Dispatchers.Default) {
                    parseHtmlToContent(html)
                    paginateContent()
                }

                val newPageCount = newPages.size.coerceAtLeast(1)
                val pageToScrollTo = if (oldChapterTitle != null) {
                    val newChapterStartIndex = newChapters.find { it.title == oldChapterTitle }?.startIndex ?: 0
                    (newChapterStartIndex + oldItemInChapter).coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
                } else {
                    (oldPercent * newPageCount).toInt().coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
                }

                val newPercent = (pageToScrollTo.toFloat() / newPageCount) * 100f
                _uiState.value = _uiState.value.copy(
                    htmlList = newPages,
                    chapterList = newChapters,
                    initPage = pageToScrollTo,
                    isError = false
                )
                _currentPercentage.value = newPercent
                showLoadingScrim = false
                isTransitioning = false
            }
        } else {
            _uiState.value = _uiState.value.copy(initPage = currentPage)
            onSetView(_uiState.value.currentView, forceReload = true)
        }
    }

    fun onSetBackgroundColor(color: Color?) {
        _uiState.value = _uiState.value.copy(backgroundColor = color, nightMode = false)
    }

    override fun onCleared() {
        if (initialized) saveHistory(latestPage)
        nextHtmlList = null
        nextChapterList = null
        isPreloading = false
        super.onCleared()
    }

    fun setTranslationMode(mode: Int, currentPage: Int) {
        if (_uiState.value.translationMode == mode) return
        _uiState.value = _uiState.value.copy(translationMode = mode)
        saveCurrentSettings()

        val html = currentRawHtml
        if (html != null) {
            viewModelScope.launch {
                showLoadingScrim = true
                val realPageCount = _uiState.value.htmlList.size
                val oldPageCount = realPageCount.coerceAtLeast(1)
                val oldPercent = currentPage.toFloat() / oldPageCount
                val (oldChapterTitle, oldItemInChapter) = if (currentPage in 0..<realPageCount) {
                    val title = _uiState.value.htmlList[currentPage].chapterTitle
                    val startIndex = _uiState.value.chapterList.find { it.title == title }?.startIndex ?: 0
                    Pair(title, (currentPage - startIndex).coerceAtLeast(0))
                } else Pair(null, 0)

                val (newPages, newChapters) = withContext(Dispatchers.Default) {
                    parseHtmlToContent(html)
                    paginateContent()
                }

                val newPageCount = newPages.size.coerceAtLeast(1)
                val pageToScrollTo = if (oldChapterTitle != null) {
                    val newChapterStartIndex = newChapters.find { it.title == oldChapterTitle }?.startIndex ?: 0
                    (newChapterStartIndex + oldItemInChapter).coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
                } else {
                    (oldPercent * newPageCount).toInt().coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
                }

                val newPercent = (pageToScrollTo.toFloat() / newPageCount) * 100f
                _uiState.value = _uiState.value.copy(
                    htmlList = newPages,
                    chapterList = newChapters,
                    initPage = pageToScrollTo,
                    isError = false
                )
                _currentPercentage.value = newPercent
                showLoadingScrim = false
                isTransitioning = false
            }
        }
    }

    fun setFontFamily(fontFamily: Int) {
        if (_uiState.value.fontFamily == fontFamily) return
        _uiState.value = _uiState.value.copy(fontFamily = fontFamily)
        saveCurrentSettings()
    }

    fun updateFontRatios() {
        val state = _uiState.value
        val typeface = typefaceFromMode(state.fontFamily)
        val fontSizePx = ValueUtil.spToPx(state.fontSize)
        currentAsciiRatios = FontMetricsUtil.getAsciiWidthRatios(typeface, fontSizePx)
    }
}