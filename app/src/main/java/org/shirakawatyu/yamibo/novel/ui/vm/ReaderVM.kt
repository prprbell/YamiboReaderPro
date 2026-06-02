package org.shirakawatyu.yamibo.novel.ui.vm

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.LruCache
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
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.shirakawatyu.yamibo.novel.util.reader.ReaderReturnBridge
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.reader.CacheData
import org.shirakawatyu.yamibo.novel.util.reader.CacheUtil
import org.shirakawatyu.yamibo.novel.util.reader.ChineseConvertUtil
import org.shirakawatyu.yamibo.novel.util.reader.FontMetricsUtil
import org.shirakawatyu.yamibo.novel.util.reader.HTMLUtil
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import org.shirakawatyu.yamibo.novel.util.reader.TextUtil
import org.shirakawatyu.yamibo.novel.util.reader.ValueUtil
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@SuppressLint("SetJavaScriptEnabled")
class ReaderVM(private val applicationContext: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderState())
    val uiState = _uiState.asStateFlow()
    private val _currentPercentage = MutableStateFlow(0f)
    val currentPercentage =  _currentPercentage.asStateFlow()

    private var pagerState: PagerState? = null
    private var maxHeight = 0.dp
    private var maxWidth = 0.dp
    private var initialized = false
    private val logTag = "ReaderVM"
    private var compositionScope: CoroutineScope? = null
    private var pageEnterTime = 0L
    private var loadJob: Job? = null
    private var loadRequestId = 0

    /**
     * Reader 的解析 / 简繁转换 / 分页都属于 CPU 密集任务。
     * 使用单独的单线程 dispatcher，避免在切换简繁或大幅改字体时把 Default 线程池打满，影响动画帧。
     */
    private val readerLayoutDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReaderLayout").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    var url by mutableStateOf("")
        private set
    // 原始入口 URL，只用于兼容旧版本缓存 key。
    // ReaderVM.url 本身会统一为 FavoriteUtil.normalizeUrl(cleanUrl)。
    private var rawReaderUrl: String = ""

    private fun readerIdentityAliases(): List<String> {
        val aliases = mutableListOf<String>()

        if (rawReaderUrl.isNotBlank()) {
            aliases += rawReaderUrl
            aliases += ReaderReturnBridge.toAbsoluteBbsUrl(rawReaderUrl)
            aliases += ReaderReturnBridge.stripReaderTransientParams(
                ReaderReturnBridge.toAbsoluteBbsUrl(rawReaderUrl)
            )
        }

        if (url.isNotBlank()) {
            aliases += ReaderReturnBridge.toAbsoluteBbsUrl(url)
            aliases += ReaderReturnBridge.stripReaderTransientParams(
                ReaderReturnBridge.toAbsoluteBbsUrl(url)
            )
        }

        return aliases
            .map { it.trim() }
            .filter { it.isNotBlank() && it != url }
            .distinct()
    }

    private suspend fun getMemoryCacheCompat(pageNum: Int): CacheData? {
        return suspendCoroutine { cont ->
            CacheUtil.getCacheCompat(
                primaryUrl = url,
                pageNum = pageNum,
                aliasUrls = readerIdentityAliases()
            ) { data ->
                cont.resume(data)
            }
        }
    }

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
    private var nextRawHtml: String? = null

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

    /**
     * 非普通收藏入口的缓存身份。
     *
     * 普通 OtherWebPage FAB 进入 ReaderPage 时不开放磁盘缓存，
     * 因为缓存管理缺少稳定标题/归属。
     * 但如果 OtherWebPage 命中了 ReaderReturnBridge 的同 tid 上下文，
     * 就可以复用原 ReaderPage 的稳定 URL 和标题，让缓存仍然能在管理页被识别。
     */
    private var externalCacheIdentityEnabled: Boolean = false
    private var externalCacheTitle: String? = null

    private var currentCacheSessionShowsProgress: Boolean = true
    private var currentAsciiRatios: FloatArray = FloatArray(128) { 0.5f }

    /**
     * rawContentList 的稳定版本号。
     * 版本号由 HTML、网页页码、简繁模式、图片模式计算，不再每次 parse 都递增。
     * 这样切回已解析过的简繁模式或图片模式时，layoutCache 才有机会命中。
     */
    private var rawContentListVersion: Long = 0L

    /**
     * 只缓存“正文分页结果”，不缓存尾部的“正在加载/刷新本页/下一页预览”。
     * 因为尾部内容依赖 nextHtmlList 和 currentView/maxWebView，是动态状态。
     */
    private val layoutCache = LruCache<LayoutCacheKey, List<Content>>(12)

    /**
     * 缓存 HTML -> Content 的解析结果。
     * 这层缓存能跳过 Jsoup、HTMLUtil 和 OpenCC，尤其对“原文/繁体/简体”来回切换很有用。
     */
    private val parsedContentCache = LruCache<ParsedContentCacheKey, List<Content>>(12)

    private data class ParsedContentCacheKey(
        val url: String,
        val webPage: Int,
        val htmlLength: Int,
        val htmlHash: Int,
        val translationMode: Int,
        val loadImages: Boolean
    )

    private data class LayoutCacheKey(
        val url: String,
        val rawVersion: Long,
        val widthPx: Int,
        val heightPx: Int,
        val fontSizePx: Int,
        val lineHeightPx: Int,
        val letterSpacingPx: Int,
        val paddingPx: Int,
        val isVerticalMode: Boolean,
        val fontFamily: Int,
        val translationMode: Int,
        val loadImages: Boolean
    )

    /**
     * 重新分页时用的阅读位置锚点。
     * - chapterIndex：用章节序号，而不是章节标题，避免简繁转换后标题变化导致匹配失败。
     * - charOffsetInChapter / chapterProgress：字体、行高、padding 改变后，按章节内文本进度恢复位置。
     * - totalProgress：极端情况下的兜底。
     */
    private data class PageAnchor(
        val totalProgress: Float,
        val chapterIndex: Int?,
        val chapterProgress: Float,
        val charOffsetInChapter: Int
    )

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
        _cachedPages.value = localCache.getCachedPageNumsCompat(
            primaryUrl = url,
            aliasUrls = readerIdentityAliases()
        )
    }

    fun setExternalCacheIdentity(enabled: Boolean, title: String?) {
        externalCacheIdentityEnabled = enabled
        externalCacheTitle = title
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun scheduleDiskCacheRefresh() {
        if (url.isBlank() || currentAuthorId == null) return
        ReaderReturnBridge.pendingCacheRefresh = ReaderReturnBridge.PendingCacheRefresh(
            url = url,
            pageNum = _uiState.value.currentView,
            authorId = currentAuthorId,
            cacheTitle = cacheTitleForDisk()
        )
    }

    private fun cacheTitleForDisk(): String? {
        return if (externalCacheIdentityEnabled) externalCacheTitle else null
    }

    // ==================== 磁盘缓存功能 ====================
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
        withContext(Dispatchers.IO) { localCache.savePage(url, pageNum, cacheData, diskCacheIncludeImages, cacheTitleForDisk()) }
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

        viewModelScope.launch {
            val memoryCacheData = getMemoryCacheCompat(pageNum)

            if (memoryCacheData != null) {
                withContext(Dispatchers.IO) {
                    localCache.savePage(
                        novelUrl = url,
                        pageNum = pageNum,
                        data = memoryCacheData,
                        hasImages = diskCacheIncludeImages,
                        title = cacheTitleForDisk()
                    )
                }

                _cachedPages.value += pageNum
                diskCacheQueue.remove(pageNum)
                loadNextPageForDiskCache(false)
            } else {
                loadPageForDiskCache(pageNum)
            }
        }
    }

    fun deleteCachedPages(pagesToDelete: Set<Int>) {
        viewModelScope.launch {
            try {
                pagesToDelete.forEach { pageNum ->
                    localCache.deletePageCompat(
                        primaryUrl = url,
                        pageNum = pageNum,
                        aliasUrls = readerIdentityAliases()
                    )

                    CacheUtil.clearCacheEntryCompat(
                        primaryUrl = url,
                        pageNum = pageNum,
                        aliasUrls = readerIdentityAliases()
                    )
                }

                _cachedPages.value -= pagesToDelete
            } catch (e: Exception) {
                Log.e(logTag, "Failed to delete cached pages", e)
            }
        }
    }

    fun updateCachedPages(pagesToUpdate: Set<Int>, includeImages: Boolean, showProgressDialog: Boolean = true) {
        viewModelScope.launch {
            try {
                pagesToUpdate.forEach { pageNum ->
                    localCache.deletePageCompat(
                        primaryUrl = url,
                        pageNum = pageNum,
                        aliasUrls = readerIdentityAliases()
                    )

                    CacheUtil.clearCacheEntryCompat(
                        primaryUrl = url,
                        pageNum = pageNum,
                        aliasUrls = readerIdentityAliases()
                    )
                }

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

            var cleanUrl = initUrl.substringBefore("?")
            val queryString = initUrl.substringAfter("?", "")
            if (queryString.isNotEmpty()) {
                val keptParams = queryString.split("&").filter { param ->
                    val key = param.substringBefore("=")
                    key != "page" && key != "authorid"
                }
                if (keptParams.isNotEmpty()) {
                    cleanUrl += "?" + keptParams.joinToString("&")
                }
            }
            rawReaderUrl = cleanUrl
            url = FavoriteUtil.normalizeUrl(cleanUrl)
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
                    fontFamily = settings?.fontFamily ?: 0
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
                    val normalizedUrl = FavoriteUtil.normalizeUrl(url)
                    val isFavorited = favorites.any { it.url == normalizedUrl }
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

            val localData = withContext(Dispatchers.IO) {
                localCache.loadPageCompat(
                    primaryUrl = url,
                    pageNum = targetView,
                    aliasUrls = readerIdentityAliases()
                )
            }

            if (localData != null) {
                if (localData.authorId == currentAuthorId || currentAuthorId == null) {
                    if (currentAuthorId == null && localData.authorId != null) {
                        currentAuthorId = localData.authorId
                    }

                    _uiState.value = _uiState.value.copy(
                        currentView = targetView,
                        maxWebView = localData.maxPageNum
                    )

                    loadFinished(
                        success = true,
                        html = localData.htmlContent,
                        loadedUrl = null,
                        maxPage = localData.maxPageNum,
                        isFromCache = true,
                        cacheTargetIndex = targetIndex,
                        targetView = targetView
                    )
                    return@launch
                }
            }

            // 内存缓存
            val memData = getMemoryCacheCompat(targetView)
            if (memData != null && (memData.authorId == currentAuthorId || currentAuthorId == null)) {
                if (currentAuthorId == null && memData.authorId != null) {
                    currentAuthorId = memData.authorId
                }

                _uiState.value = _uiState.value.copy(
                    currentView = targetView,
                    maxWebView = memData.maxPageNum
                )

                loadFinished(
                    success = true,
                    html = memData.htmlContent,
                    loadedUrl = null,
                    maxPage = memData.maxPageNum,
                    isFromCache = true,
                    cacheTargetIndex = targetIndex,
                    targetView = targetView
                )
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

        val previousView = _uiState.value.currentView
        val previousPage = latestPage

        if (initialized) {
            saveHistory(
                pageToSave = previousPage,
                webViewToSave = previousView
            )
        }

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
            currentRawHtml = nextRawHtml ?: currentRawHtml
            nextHtmlList = null
            nextChapterList = null
            nextRawHtml = null
            latestPage = 0
            showLoadingScrim = false

            saveHistory(
                pageToSave = 0,
                webViewToSave = view
            )

            return
        }

        // 清理预加载状态
        nextHtmlList = null
        nextChapterList = null
        nextRawHtml = null
        isPreloading = false

        if (forceReload) {
            CacheUtil.clearCacheEntryCompat(
                primaryUrl = url,
                pageNum = view,
                aliasUrls = readerIdentityAliases()
            )
        }

        // 立即更新 currentView 并显示遮罩
        _uiState.value = _uiState.value.copy(currentView = view, isError = false)
        _currentPercentage.value = 0f
        isTransitioning = true
        showLoadingScrim = true

        loadJob = viewModelScope.launch(Dispatchers.Main) {
            val localData = withContext(Dispatchers.IO) {
                localCache.loadPageCompat(
                    primaryUrl = url,
                    pageNum = view,
                    aliasUrls = readerIdentityAliases()
                )
            }

            if (thisRequestId != loadRequestId) return@launch

            if (localData != null && (localData.authorId == currentAuthorId || currentAuthorId == null)) {
                if (currentAuthorId == null && localData.authorId != null) {
                    currentAuthorId = localData.authorId
                }

                _uiState.value = _uiState.value.copy(
                    initPage = 0,
                    maxWebView = localData.maxPageNum
                )

                loadFinished(
                    success = true,
                    html = localData.htmlContent,
                    loadedUrl = null,
                    maxPage = localData.maxPageNum,
                    isFromCache = true,
                    cacheTargetIndex = 0,
                    targetView = view
                )
                return@launch
            }

            val memData = getMemoryCacheCompat(view)
            if (thisRequestId != loadRequestId) return@launch
            if (memData != null && (memData.authorId == currentAuthorId || currentAuthorId == null)) {
                if (currentAuthorId == null && memData.authorId != null) {
                    currentAuthorId = memData.authorId
                }

                _uiState.value = _uiState.value.copy(
                    initPage = 0,
                    maxWebView = memData.maxPageNum
                )

                loadFinished(
                    success = true,
                    html = memData.htmlContent,
                    loadedUrl = null,
                    maxPage = memData.maxPageNum,
                    isFromCache = true,
                    cacheTargetIndex = 0,
                    targetView = view
                )
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

            val (passages, chapters) = withContext(readerLayoutDispatcher) {
                parseHtmlToContent(html, targetView)
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
                    launch(Dispatchers.IO) { localCache.savePage(url, targetView, cacheData, false, cacheTitleForDisk()) }
                }
                nextHtmlList = passages
                nextChapterList = chapters
                nextRawHtml = html
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
                        launch(Dispatchers.IO) { localCache.savePage(url, targetView, cacheData, false, cacheTitleForDisk()) }
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

                if (initialized) {
                    saveHistory(
                        pageToSave = safeInitPage,
                        webViewToSave = targetView
                    )
                }
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
            nextRawHtml = null
            isPreloading = false
            layoutCache.evictAll()
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

    private fun triggerPreload(targetView: Int, maxView: Int) {
        if (isPreloading || targetView > maxView || _isDiskCaching.value) return
        isPreloading = true
        viewBeingPreloaded = targetView
        nextRawHtml = null
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

                val (passages, chapters) = withContext(readerLayoutDispatcher) {
                    val preloadContent = parseHtmlToContentList(
                        html = combinedHtml,
                        translationMode = _uiState.value.translationMode,
                        loadImages = _uiState.value.loadImages
                    )
                    paginateContent(
                        isFromCache = false,
                        targetWebPage = targetView,
                        contentOverride = preloadContent,
                        rawVersionOverride = preloadRawVersion(targetView, combinedHtml)
                    )
                }

                nextHtmlList = passages
                nextChapterList = chapters
                nextRawHtml = combinedHtml

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

    private fun parsedRawVersion(
        targetView: Int,
        html: String,
        translationMode: Int,
        loadImages: Boolean
    ): Long {
        var result = targetView.toLong()
        result = result * 31 + html.length
        result = result * 31 + html.hashCode()
        result = result * 31 + translationMode
        result = result * 31 + if (loadImages) 1 else 0
        return result
    }

    private fun parseHtmlToContent(
        html: String,
        targetView: Int = _uiState.value.currentView
    ) {
        val state = _uiState.value
        val cacheKey = ParsedContentCacheKey(
            url = url,
            webPage = targetView,
            htmlLength = html.length,
            htmlHash = html.hashCode(),
            translationMode = state.translationMode,
            loadImages = state.loadImages
        )

        val parsed = parsedContentCache.get(cacheKey) ?: parseHtmlToContentList(
            html = html,
            translationMode = state.translationMode,
            loadImages = state.loadImages
        ).also { parsedContentCache.put(cacheKey, it) }

        rawContentList.clear()
        rawContentList.addAll(parsed)
        rawContentListVersion = parsedRawVersion(
            targetView = targetView,
            html = html,
            translationMode = state.translationMode,
            loadImages = state.loadImages
        )
    }

    private fun parseHtmlToContentList(
        html: String,
        translationMode: Int,
        loadImages: Boolean
    ): List<Content> {
        val result = ArrayList<Content>()
        val doc = Jsoup.parse(html)
        doc.getElementsByTag("i").forEach { it.remove() }

        val messageNodes = doc.getElementsByClass("message")
        if (messageNodes.isEmpty()) return result

        val rawTexts = messageNodes.map { HTMLUtil.toText(it.html()) }
        val delimiter = "|||YAMIBO_SEP|||"
        val combinedText = rawTexts.joinToString(delimiter)

        val convertedCombinedText = if (combinedText.isNotBlank()) {
            when (translationMode) {
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
                result.add(Content(rawText, ContentType.TEXT, currentValidTitle))
            }

            if (loadImages) {
                for (element in node.getElementsByTag("img")) {
                    var src = element.attr("zoomfile")
                    if (src.isBlank()) src = element.attr("file")
                    if (src.isBlank()) src = element.attr("src")
                    if (src.isBlank() || src.contains("smiley/")) continue

                    val normalizedSrc = if (!src.startsWith("http://") && !src.startsWith("https://")) {
                        "${RequestConfig.BASE_URL}/${src}"
                    } else {
                        src
                    }
                    result.add(Content(normalizedSrc, ContentType.IMG, currentValidTitle))
                }
            }
        }

        return result
    }

    private fun preloadRawVersion(targetView: Int, html: String): Long {
        val state = _uiState.value
        return parsedRawVersion(
            targetView = targetView,
            html = html,
            translationMode = state.translationMode,
            loadImages = state.loadImages
        )
    }

    private fun paginateContent(
        isFromCache: Boolean = false,
        targetWebPage: Int? = null,
        contentOverride: List<Content>? = null,
        rawVersionOverride: Long? = null
    ): Pair<List<Content>, List<ChapterInfo>> {
        updateFontRatios()
        val contentSnapshot = contentOverride ?: rawContentList.toList()
        val effectiveRawVersion = rawVersionOverride ?: rawContentListVersion
        val state = _uiState.value
        val webPageNum = targetWebPage ?: state.currentView

        val pageContentWidth = maxWidth - (state.padding + state.padding)
        val topPadding = 24.dp
        val footerHeight = 87.dp
        val pageContentHeight = maxHeight - topPadding - footerHeight

        val cacheKey = LayoutCacheKey(
            url = url,
            rawVersion = effectiveRawVersion,
            widthPx = ValueUtil.dpToPx(pageContentWidth).toInt(),
            heightPx = ValueUtil.dpToPx(pageContentHeight).toInt(),
            fontSizePx = ValueUtil.spToPx(state.fontSize).toInt(),
            lineHeightPx = ValueUtil.spToPx(state.lineHeight).toInt(),
            letterSpacingPx = ValueUtil.spToPx(state.letterSpacing).toInt(),
            paddingPx = ValueUtil.dpToPx(state.padding).toInt(),
            isVerticalMode = state.isVerticalMode,
            fontFamily = state.fontFamily,
            translationMode = state.translationMode,
            loadImages = state.loadImages
        )

        val basePassages = layoutCache.get(cacheKey) ?: buildBasePassages(
            contentSnapshot = contentSnapshot,
            state = state,
            pageContentWidth = pageContentWidth,
            pageContentHeight = pageContentHeight
        ).also { layoutCache.put(cacheKey, it) }

        val passages = ArrayList<Content>(basePassages.size + 1).apply {
            addAll(basePassages)
            addDynamicFooterOrPreview(webPageNum, state)
        }

        val chapterList = buildChapterList(passages)
        return Pair(passages, chapterList)
    }

    private fun buildBasePassages(
        contentSnapshot: List<Content>,
        state: ReaderState,
        pageContentWidth: Dp,
        pageContentHeight: Dp
    ): List<Content> {
        return if (state.isVerticalMode) {
            TextUtil.pagingTextVertical(
                rawContentList = contentSnapshot,
                width = pageContentWidth,
                fontSize = state.fontSize,
                letterSpacing = state.letterSpacing,
                charRatios = currentAsciiRatios,
                typeface = typefaceFromMode(state.fontFamily)
            )
        } else {
            val passagesList = ArrayList<Content>()
            for (content in contentSnapshot) {
                when (content.type) {
                    ContentType.TEXT -> {
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
                    }
                    ContentType.IMG -> passagesList.add(content)
                }
            }
            passagesList
        }
    }

    private fun MutableList<Content>.addDynamicFooterOrPreview(webPageNum: Int, state: ReaderState) {
        if (nextHtmlList != null && nextHtmlList!!.isNotEmpty()) {
            add(nextHtmlList!!.first())
        } else if (webPageNum < state.maxWebView) {
            add(Content("正在加载...", ContentType.TEXT, "footer"))
        } else {
            add(Content("刷新本页内容", ContentType.TEXT, "footer"))
        }
    }

    private fun buildChapterList(passages: List<Content>): List<ChapterInfo> {
        val chapterList = mutableListOf<ChapterInfo>()
        var lastTitle: String? = null
        passages.forEachIndexed { index, content ->
            if (content.chapterTitle != null && content.chapterTitle != lastTitle && content.chapterTitle != "footer") {
                chapterList.add(ChapterInfo(title = content.chapterTitle, startIndex = index))
                lastTitle = content.chapterTitle
            }
        }
        return chapterList
    }

    private fun processPageChange(newPage: Int) {
        val oldPage = latestPage
        val state = _uiState.value
        val list = state.htmlList

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
            currentRawHtml = nextRawHtml ?: currentRawHtml
            nextHtmlList = null
            nextChapterList = null
            nextRawHtml = null
            latestPage = 0
            showLoadingScrim = false
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

    fun saveCurrentHistory() {
        if (initialized) {
            saveHistory(
                pageToSave = latestPage,
                webViewToSave = _uiState.value.currentView
            )
        }
    }

    private fun saveHistory(
        pageToSave: Int,
        webViewToSave: Int = _uiState.value.currentView
    ) {
        if (url.isBlank()) return

        val state = _uiState.value
        val currentList = state.htmlList
        val safePage = pageToSave.coerceIn(0, (currentList.size - 1).coerceAtLeast(0))

        val currentChapter = currentList
            .getOrNull(safePage)
            ?.chapterTitle
            ?.takeIf { it != "footer" }

        val valueToSave: Int = if (state.isVerticalMode) {
            val avgItemsPerPage = getAvgItemsPerHorizontalPage()
            (safePage.toFloat() / avgItemsPerPage.toFloat()).toInt()
        } else {
            safePage
        }

        val urlToSave = FavoriteUtil.normalizeUrl(url)
        val authorIdToSave = currentAuthorId

        viewModelScope.launch(Dispatchers.IO) {
            val map = FavoriteUtil.getFavoriteMapSuspend()
            val fav = map[urlToSave] ?: return@launch

            if (fav.lastPage == valueToSave &&
                fav.lastView == webViewToSave &&
                fav.lastChapter == currentChapter &&
                fav.authorId == authorIdToSave
            ) return@launch

            FavoriteUtil.updateFavoriteSuspend(
                fav.copy(
                    lastPage = valueToSave,
                    lastView = webViewToSave,
                    lastChapter = currentChapter,
                    authorId = authorIdToSave
                )
            )
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

    private fun contentCharLength(content: Content): Int {
        return if (content.type == ContentType.TEXT) {
            content.data.length.coerceAtLeast(1)
        } else {
            // 图片也占一个位置，避免“章节里全是图片”时进度无法前进。
            1
        }
    }

    private fun contentEndExclusive(pages: List<Content>): Int {
        val footerIndex = pages.indexOfFirst { it.chapterTitle == "footer" }
        return if (footerIndex >= 0) footerIndex else pages.size
    }

    private fun capturePageAnchor(currentPage: Int): PageAnchor {
        val state = _uiState.value
        val pages = state.htmlList
        val contentEnd = contentEndExclusive(pages)
        val totalItems = contentEnd.coerceAtLeast(1)
        val safePage = currentPage.coerceIn(0, (totalItems - 1).coerceAtLeast(0))

        val chapterIndex = state.chapterList
            .indexOfLast { it.startIndex <= safePage }
            .takeIf { it >= 0 }

        if (chapterIndex == null) {
            return PageAnchor(
                totalProgress = safePage.toFloat() / totalItems.toFloat(),
                chapterIndex = null,
                chapterProgress = 0f,
                charOffsetInChapter = 0
            )
        }

        val chapterStart = state.chapterList[chapterIndex].startIndex
        val chapterEndExclusive = state.chapterList
            .getOrNull(chapterIndex + 1)
            ?.startIndex
            ?: contentEnd

        val safeChapterStart = chapterStart.coerceIn(0, contentEnd)
        val safeChapterEnd = chapterEndExclusive.coerceIn(safeChapterStart, contentEnd)

        var totalCharsInChapter = 0
        var charsBeforeCurrentPage = 0

        for (i in safeChapterStart until safeChapterEnd) {
            val len = contentCharLength(pages[i])
            if (i < safePage) charsBeforeCurrentPage += len
            totalCharsInChapter += len
        }

        val chapterProgress = if (totalCharsInChapter > 0) {
            charsBeforeCurrentPage.toFloat() / totalCharsInChapter.toFloat()
        } else {
            0f
        }

        return PageAnchor(
            totalProgress = safePage.toFloat() / totalItems.toFloat(),
            chapterIndex = chapterIndex,
            chapterProgress = chapterProgress.coerceIn(0f, 1f),
            charOffsetInChapter = charsBeforeCurrentPage
        )
    }

    private fun resolvePageAnchor(
        anchor: PageAnchor,
        newPages: List<Content>,
        newChapters: List<ChapterInfo>,
        preferChapterProgress: Boolean = false
    ): Int {
        val newPageCount = newPages.size.coerceAtLeast(1)
        val maxIndex = (newPageCount - 1).coerceAtLeast(0)

        val contentEnd = contentEndExclusive(newPages)
        val chapterIndex = anchor.chapterIndex
        if (chapterIndex != null && chapterIndex in newChapters.indices) {
            val chapterStart = newChapters[chapterIndex].startIndex.coerceIn(0, contentEnd)
            val chapterEndExclusive = newChapters
                .getOrNull(chapterIndex + 1)
                ?.startIndex
                ?: contentEnd
            val chapterEnd = chapterEndExclusive.coerceIn(chapterStart, contentEnd)

            var totalCharsInNewChapter = 0
            for (i in chapterStart until chapterEnd) {
                totalCharsInNewChapter += contentCharLength(newPages[i])
            }

            if (totalCharsInNewChapter <= 0) {
                return chapterStart.coerceIn(0, maxIndex)
            }

            val rawTargetCharOffset = when {
                preferChapterProgress -> {
                    (anchor.chapterProgress * totalCharsInNewChapter).toInt()
                }
                anchor.charOffsetInChapter > 0 -> {
                    anchor.charOffsetInChapter
                }
                else -> {
                    (anchor.chapterProgress * totalCharsInNewChapter).toInt()
                }
            }

            val targetCharOffset = rawTargetCharOffset
                .coerceIn(0, (totalCharsInNewChapter - 1).coerceAtLeast(0))

            var accumulated = 0
            for (i in chapterStart until chapterEnd) {
                accumulated += contentCharLength(newPages[i])
                if (accumulated > targetCharOffset) {
                    return i.coerceIn(0, maxIndex)
                }
            }

            return (chapterEnd - 1).coerceIn(0, maxIndex)
        }

        return (anchor.totalProgress * newPageCount)
            .toInt()
            .coerceIn(0, maxIndex)
    }

    private fun applyRepaginatedContent(
        newPages: List<Content>,
        newChapters: List<ChapterInfo>,
        pageToScrollTo: Int
    ) {
        val newPageCount = newPages.size.coerceAtLeast(1)
        val safePage = pageToScrollTo.coerceIn(0, (newPageCount - 1).coerceAtLeast(0))
        val newPercent = (safePage.toFloat() / newPageCount.toFloat()) * 100f

        ignoreFirstFakeZero = safePage > 0
        _uiState.value = _uiState.value.copy(
            htmlList = newPages,
            chapterList = newChapters,
            initPage = safePage,
            isError = false
        )
        _currentPercentage.value = newPercent
        latestPage = safePage
        showLoadingScrim = false
        isTransitioning = false
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
        val anchor = capturePageAnchor(currentPage)
        saveCurrentSettings()
        viewModelScope.launch {
            showLoadingScrim = true
            kotlinx.coroutines.yield()

            val (newPages, newChapters) = withContext(readerLayoutDispatcher) {
                paginateContent()
            }

            val pageToScrollTo = resolvePageAnchor(anchor, newPages, newChapters)
            applyRepaginatedContent(newPages, newChapters, pageToScrollTo)
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

        val currentPage = latestPage
        val anchor = capturePageAnchor(currentPage)
        _uiState.value = _uiState.value.copy(loadImages = load)
        saveCurrentSettings()

        val html = currentRawHtml
        if (html != null) {
            viewModelScope.launch {
                showLoadingScrim = true
                kotlinx.coroutines.yield()

                val (newPages, newChapters) = withContext(readerLayoutDispatcher) {
                    parseHtmlToContent(html, _uiState.value.currentView)
                    paginateContent()
                }

                val pageToScrollTo = resolvePageAnchor(anchor, newPages, newChapters)
                applyRepaginatedContent(newPages, newChapters, pageToScrollTo)
            }
        } else {
            _uiState.value = _uiState.value.copy(initPage = currentPage)
            onSetView(_uiState.value.currentView, forceReload = true)
        }
    }

    fun onSetBackgroundColor(color: Color?) {
        _uiState.value = _uiState.value.copy(backgroundColor = color, nightMode = false)
        saveCurrentSettings()
    }

    override fun onCleared() {
        if (initialized) saveHistory(latestPage)
        nextHtmlList = null
        nextChapterList = null
        nextRawHtml = null
        isPreloading = false
        layoutCache.evictAll()
        parsedContentCache.evictAll()
        readerLayoutDispatcher.close()
        super.onCleared()
    }

    fun setTranslationMode(mode: Int, currentPage: Int) {
        if (_uiState.value.translationMode == mode) return

        val anchor = capturePageAnchor(currentPage)
        _uiState.value = _uiState.value.copy(translationMode = mode)
        saveCurrentSettings()

        val html = currentRawHtml
        if (html != null) {
            viewModelScope.launch {
                showLoadingScrim = true
                kotlinx.coroutines.yield()

                val (newPages, newChapters) = withContext(readerLayoutDispatcher) {
                    parseHtmlToContent(html, _uiState.value.currentView)
                    paginateContent()
                }

                val pageToScrollTo = resolvePageAnchor(anchor, newPages, newChapters, preferChapterProgress = true)
                applyRepaginatedContent(newPages, newChapters, pageToScrollTo)
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