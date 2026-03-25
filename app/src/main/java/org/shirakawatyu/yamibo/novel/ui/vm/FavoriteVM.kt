package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import org.shirakawatyu.yamibo.novel.parser.MangaHtmlParser
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.LocalCacheUtil
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class FetchState { IDLE, BACKGROUND, MANUAL }

class FavoriteVM(private val applicationContext: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoriteState())
    val uiState = _uiState.asStateFlow()

    // 记录当前的刷新状态，默认为空闲
    private val currentFetchState = AtomicReference(FetchState.IDLE)

    // 请求世代ID，用于打断旧的递归任务
    private val fetchGeneration = AtomicLong(0)
    private val logTag = "FavoriteVM"
    private var allFavorites: List<Favorite> = listOf()

    // 记录最后一次成功触发刷新的时间戳
    private var lastSmartSyncTime = 0L

    // 冷却时间，5秒内不重复发起后台同步
    private val SMART_SYNC_COOLDOWN = 5_000L

    // 等待队列：保存正在倒计时的那个任务
    private var pendingSyncJob: kotlinx.coroutines.Job? = null

    private var lastNavigateTime = 0L
    private val SMART_SYNC_TIMEOUT = 60 * 1000L

    enum class RefreshStrategy {
        FULL,   // 全量刷新
        SMART,  // 增量刷新
        SKIP    // 跳过刷新
    }

    var nextResumeStrategy = RefreshStrategy.FULL
    var currentCategory: Int = -1
        private set

    // 本地缓存工具
    private val localCache by lazy { LocalCacheUtil.getInstance(applicationContext) }

    init {
        viewModelScope.launch {
            FavoriteUtil.getFavoriteFlow().collect { fullList ->
                allFavorites = fullList
                updateUiList()
                refreshCacheInfo(localCache.index.value)
                val titleMap = fullList.associate {
                    val cleanTitle = it.title.replace(Regex("^(?:【.*?】|\\[.*?\\]|\\s)+"), "").ifBlank { it.title }
                    it.url to cleanTitle
                }
                localCache.updateCacheTitles(titleMap)
            }
        }

        viewModelScope.launch {
            localCache.index.collect { index ->
                if (allFavorites.isNotEmpty()) {
                    refreshCacheInfo(index)
                }
            }
        }
    }

    fun setCategory(category: Int) {
        currentCategory = category
        updateUiList()
    }

    private fun updateUiList() {
        val currentState = _uiState.value
        val baseList = if (currentState.isInManageMode) {
            allFavorites
        } else {
            allFavorites.filter { !it.isHidden }
        }

        val filteredList = if (currentCategory == -1) {
            baseList
        } else {
            baseList.filter { it.type == currentCategory }
        }

        _uiState.value = currentState.copy(favoriteList = filteredList)
    }

    fun refreshList(showLoading: Boolean = true, isSmartSync: Boolean = false) {
        if (isSmartSync) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLast = currentTime - lastSmartSyncTime

            if (timeSinceLast < SMART_SYNC_COOLDOWN) {
                // 尝试进入等待队列

                // 队列里已经有排队任务了 (容量为1)，直接丢弃本次请求
                if (pendingSyncJob?.isActive == true) {
                    Log.d(logTag, "后台同步冷却中，且已有排队任务，丢弃本次请求")
                    return
                }

                // 计算还需要等待多久
                val waitTime = SMART_SYNC_COOLDOWN - timeSinceLast
                Log.d(logTag, "后台同步进入等待队列，将于 ${waitTime}ms 后执行")

                // 占用队列，开始倒计时
                pendingSyncJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(waitTime)
                    // 睡醒后，更新时间戳并真正执行请求
                    lastSmartSyncTime = System.currentTimeMillis()
                    executeActualRefresh(showLoading, isSmartSync = true)
                }
                return // 排队完毕，直接返回

            } else {
                // 更新时间，立刻放行
                lastSmartSyncTime = currentTime
            }
        } else {
            // 手动刷新
            pendingSyncJob?.cancel()
            lastSmartSyncTime = System.currentTimeMillis()
        }

        // 立即执行真正的刷新逻辑
        executeActualRefresh(showLoading, isSmartSync)
    }

    private fun executeActualRefresh(showLoading: Boolean, isSmartSync: Boolean) {
        val requestedState = if (isSmartSync) FetchState.BACKGROUND else FetchState.MANUAL

        // 状态判定与拦截逻辑
        while (true) {
            val currentState = currentFetchState.get()

            // 1. 同级拦截：手动拦截手动，后台拦截后台
            if (currentState == requestedState) {
                Log.d(logTag, "同类型任务正在运行，已拦截 ($requestedState)")
                return
            }

            // 2. 越级拦截：正在手动刷新时，拒绝后台刷新的介入
            if (currentState == FetchState.MANUAL && requestedState == FetchState.BACKGROUND) {
                Log.d(logTag, "手动刷新正在进行，已拦截后台请求")
                return
            }

            // 3. 状态变更：如果是IDLE，或者BACKGROUND被MANUAL覆盖
            if (currentFetchState.compareAndSet(currentState, requestedState)) {
                if (currentState == FetchState.BACKGROUND && requestedState == FetchState.MANUAL) {
                    Log.d(logTag, "手动刷新触发，即将覆盖并打断后台任务")
                }
                break
            }
        }

        // 每次放行新请求，生成一个新的“世代 ID”
        val currentGen = fetchGeneration.incrementAndGet()

        if (showLoading) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }

        CookieUtil.getCookie {
            fetchAllFavorites(
                page = 1,
                accumulatedList = ArrayList(),
                isSmartSync = isSmartSync,
                isBackground = isSmartSync,
                totalPages = 1,
                generation = currentGen
            )
        }
    }

    private fun releaseStateIfCurrent(generation: Long) {
        if (fetchGeneration.get() == generation) {
            currentFetchState.set(FetchState.IDLE)
        }
    }

    private fun fetchAllFavorites(
        page: Int,
        accumulatedList: ArrayList<Favorite>,
        isSmartSync: Boolean,
        isBackground: Boolean,
        totalPages: Int,
        generation: Long
    ) {
        // 进入递归前，检查是不是已经被覆盖的旧任务
        if (generation != fetchGeneration.get()) return

        val favoriteApi = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)

        favoriteApi.getFavoritePage(page).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                // 网络请求回来后，检查
                if (generation != fetchGeneration.get()) return
                viewModelScope.launch(Dispatchers.IO) {
                    // 切入协程后，检查
                    if (generation != fetchGeneration.get()) return@launch

                    val respHTML = response.body()?.string()
                    val pageList = mutableListOf<Favorite>()

                    if (respHTML != null) {
                        val parse = Jsoup.parse(respHTML)
                        val favList = parse.getElementsByClass("sclist")

                        favList.forEach { li ->
                            val aTag = li.select("a").last()
                            if (aTag != null) {
                                pageList.add(Favorite(aTag.text(), aTag.attr("href")))
                            }
                        }
                    }

                    if (pageList.isNotEmpty()) {
                        accumulatedList.addAll(pageList)

                        FavoriteUtil.mergeFavoritesProgressive(pageList) { hasNewItems ->
                            // 数据库合并回调回来后检查
                            if (generation != fetchGeneration.get()) return@mergeFavoritesProgressive

                            // 第一页出结果，解除UI锁定
                            if (page == 1) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                                }
                            }

                            val parse = Jsoup.parse(respHTML!!)
                            val nextPageLink = parse.select(".page a, .pg a").find {
                                it.text().contains("下一页") || it.hasClass("nxt")
                            }
                            val hasNextPage =
                                nextPageLink != null && nextPageLink.attr("href").isNotBlank()

                            var currentTotalPages = totalPages
                            var currentIsSmartSync = isSmartSync

                            // 在第1页进行智能研判
                            if (page == 1) {
                                // 提取真实的线上总页数
                                currentTotalPages = MangaHtmlParser.extractTotalPages(respHTML)
                                val maxPossibleRemoteItems = currentTotalPages * 20

                                // 容量检测：如果本地列表数量大于线上最大容量，说明网页端发生了大量删除
                                if (allFavorites.size > maxPossibleRemoteItems) {
                                    currentIsSmartSync = false
                                }
                                // 单页检测：如果总共只有一页，干脆转全量以便结束时顺手执行GC
                                else if (!hasNextPage) {
                                    currentIsSmartSync = false
                                }
                            }

                            // 决定是否继续拉取
                            val shouldContinue = if (currentIsSmartSync) {
                                // 有新内容且有下一页
                                hasNewItems && hasNextPage
                            } else {
                                hasNextPage
                            }

                            if (shouldContinue) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    if (generation != fetchGeneration.get()) return@launch

                                    if (isBackground) {
                                        // 页数越多请求越快，页数越少请求越慢
                                        val dynamicDelay =
                                            (1200L - ((currentTotalPages - 1) * 150L)).coerceIn(
                                                600L,
                                                1050L
                                            )
                                        kotlinx.coroutines.delay(dynamicDelay)
                                    } else {
                                        // 手动刷新保持激进
                                        kotlinx.coroutines.delay(100L)
                                    }
                                    fetchAllFavorites(
                                        page + 1,
                                        accumulatedList,
                                        currentIsSmartSync,
                                        isBackground,
                                        currentTotalPages,
                                        generation
                                    )
                                }
                            } else {
                                // 仅在全量模式结束时，执行本地垃圾清理
                                if (!currentIsSmartSync) {
                                    FavoriteUtil.cleanupDeletedFavorites(accumulatedList)
                                }
                                // 正常结束，释放锁
                                releaseStateIfCurrent(generation)
                            }
                        }
                    } else {
                        if (page == 1) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(isRefreshing = false)
                            }

                            // 直接通过Cookie里的auth字段判断真实登录状态
                            val isLoggedIn = GlobalData.currentCookie.contains("EeqY_2132_auth=")
                            val htmlStr = respHTML ?: ""
                            // 如果确实未登录，或者这是一次全量刷新，则清空本地数据
                            if (!isLoggedIn || !isSmartSync || htmlStr.contains("您还没有添加任何收藏")) {
                                FavoriteUtil.cleanupDeletedFavorites(emptyList())
                            }
                        }
                        // 第一页没数据，释放锁
                        releaseStateIfCurrent(generation)
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                // 已经是旧任务
                if (generation != fetchGeneration.get()) return

                t.printStackTrace()
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
                // 网络失败，释放锁
                releaseStateIfCurrent(generation)
            }
        })
    }

    fun clickHandler(favorite: Favorite, navController: NavController) {
        val urlEncoded = URLEncoder.encode(favorite.url, "utf-8")
        lastNavigateTime = System.currentTimeMillis()
        nextResumeStrategy = RefreshStrategy.SMART

        when (favorite.type) {
            1 -> {
                nextResumeStrategy = RefreshStrategy.SKIP // 看小说，默认不刷新
                navController.navigate("ReaderPage/$urlEncoded")
            }

            2 -> {
                nextResumeStrategy = RefreshStrategy.SKIP // 看漫画，默认不刷新
                val targetUrl = favorite.lastMangaUrl ?: favorite.url
                val encodedTarget = URLEncoder.encode(targetUrl, "utf-8")
                val encodedOriginal = URLEncoder.encode(favorite.url, "utf-8")
                navController.navigate("MangaWebPage/$encodedTarget/$encodedOriginal?fastForward=false&initialPage=${favorite.lastPage}")
            }

            3 -> {
                nextResumeStrategy = RefreshStrategy.SMART // 直接去网页版，可能去其他页面点收藏，保持SMART
                navController.navigate("OtherWebPage/$urlEncoded")
            }

            else -> {
                nextResumeStrategy = RefreshStrategy.SMART
                navController.navigate("ProbingPage/$urlEncoded")
            }
        }
    }

    fun getEffectiveResumeStrategy(): RefreshStrategy {
        if (nextResumeStrategy == RefreshStrategy.SKIP) {
            val elapsed = System.currentTimeMillis() - lastNavigateTime
            if (elapsed > SMART_SYNC_TIMEOUT) {
                return RefreshStrategy.SMART
            }
        }
        return nextResumeStrategy
    }

    fun updateMangaProgress(
        favoriteUrl: String,
        chapterUrl: String,
        chapterTitle: String,
        pageIndex: Int = 0
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                if (fav.url == favoriteUrl) {
                    fav.copy(
                        lastMangaUrl = chapterUrl,
                        lastChapter = chapterTitle,
                        lastPage = pageIndex
                    )
                } else {
                    fav
                }
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) {
                updateUiList()
            }
        }
    }

    // 3. 拖拽排序
    fun moveFavorite(from: Int, to: Int) {
        if (_uiState.value.isInManageMode) return

        val currentUiList = _uiState.value.favoriteList.toMutableList()
        if (from < 0 || from >= currentUiList.size || to < 0 || to >= currentUiList.size || from == to) return

        val item = currentUiList.removeAt(from)
        currentUiList.add(to, item)

        _uiState.value = _uiState.value.copy(favoriteList = currentUiList.toList())

        viewModelScope.launch(Dispatchers.IO) {
            val categoryUrls = currentUiList.map { it.url }.toSet()
            val newQueue = java.util.LinkedList(currentUiList)

            val newListToSave = allFavorites.map { fav ->
                if (categoryUrls.contains(fav.url)) {
                    newQueue.poll() ?: fav
                } else {
                    fav
                }
            }

            allFavorites = newListToSave
            FavoriteUtil.saveFavoriteOrder(newListToSave)
        }
    }

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(
            isInManageMode = !_uiState.value.isInManageMode,
            selectedItems = emptySet()
        )
        updateUiList()
    }

    fun toggleItemSelection(url: String) {
        if (!_uiState.value.isInManageMode) return

        val newSelections = _uiState.value.selectedItems.toMutableSet()
        if (newSelections.contains(url)) newSelections.remove(url)
        else newSelections.add(url)

        _uiState.value = _uiState.value.copy(selectedItems = newSelections)
    }

    fun hideSelectedItems() {
        val itemsToHide = _uiState.value.selectedItems
        if (itemsToHide.isEmpty()) return
        viewModelScope.launch {
            FavoriteUtil.updateHiddenStatus(itemsToHide, true) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedItems = emptySet())
                }
            }
        }
    }

    fun unhideSelectedItems() {
        val itemsToUnhide = _uiState.value.selectedItems
        if (itemsToUnhide.isEmpty()) return
        viewModelScope.launch {
            FavoriteUtil.updateHiddenStatus(itemsToUnhide, false) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedItems = emptySet())
                }
            }
        }
    }

    private fun refreshCacheInfo(index: Map<String, LocalCacheUtil.CacheIndex>) {
        try {
            val cacheInfoMap = mutableMapOf<String, CacheInfo>()
            index.forEach { (url, novelCache) ->
                if (novelCache.pages.isNotEmpty()) {
                    val totalPages = novelCache.pages.size
                    val totalSize = novelCache.pages.values.sumOf { it.fileSize }
                    val pagesWithImages = novelCache.pages.values.count { it.hasImages }
                    cacheInfoMap[url] = CacheInfo(url, totalPages, totalSize, pagesWithImages, novelCache.title)
                }
            }
            _uiState.value = _uiState.value.copy(cacheInfoMap = cacheInfoMap)
        } catch (e: Exception) {
            Log.e(logTag, "从内存索引刷新缓存信息失败", e)
            _uiState.value = _uiState.value.copy(cacheInfoMap = emptyMap())
        }
    }

    data class CacheInfo(
        val url: String,
        val totalPages: Int,
        val totalSize: Long,
        val pagesWithImages: Int,
        val title: String? = null
    )

    fun refreshCacheInfo() = refreshCacheInfo(localCache.index.value)

    fun getCacheInfo(callback: (Map<String, CacheInfo>) -> Unit) {
        refreshCacheInfo(localCache.index.value)
        callback(_uiState.value.cacheInfoMap)
    }

    fun deleteFavoriteCache(url: String) {
        viewModelScope.launch {
            try {
                localCache.deleteNovel(url)
            } catch (e: Exception) {
                Log.e(logTag, "删除 $url 的缓存失败", e)
            }
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            try {
                localCache.clearAllCache()
            } catch (e: Exception) {
                Log.e(logTag, "清除所有缓存失败", e)
            }
        }
    }

    fun clearBookmark(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                if (fav.url == url) fav.copy(
                    lastPage = 0,
                    lastView = 1,
                    lastChapter = null,
                    lastMangaUrl = null
                ) else fav
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) { updateUiList() }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                fav.copy(lastPage = 0, lastView = 1, lastChapter = null, lastMangaUrl = null)
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) { updateUiList() }
        }
    }

    fun getDirectoryList(callback: (List<org.shirakawatyu.yamibo.novel.bean.MangaDirectory>) -> Unit) {
        viewModelScope.launch {
            val repo = org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            )
            viewModelScope.launch(Dispatchers.Main) { callback(repo.getAllDirectories()) }
        }
    }

    fun deleteDirectory(cleanName: String, callback: () -> Unit) {
        viewModelScope.launch {
            org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            ).deleteDirectory(cleanName)
            viewModelScope.launch(Dispatchers.Main) { callback() }
        }
    }

    fun clearAllDirectories(callback: () -> Unit) {
        viewModelScope.launch {
            org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            ).clearAllDirectories()
            viewModelScope.launch(Dispatchers.Main) { callback() }
        }
    }
}