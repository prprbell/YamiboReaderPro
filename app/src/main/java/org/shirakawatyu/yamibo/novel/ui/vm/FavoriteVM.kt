package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import org.shirakawatyu.yamibo.novel.parser.MangaHtmlParser
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteDeleteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class FetchState { IDLE, BACKGROUND, MANUAL }

class FavoriteVM(private val applicationContext: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoriteState())
    val uiState = _uiState.asStateFlow()
    private val stateMutex = Mutex()
    // 记录当前的刷新状态，默认为空闲
    private val currentFetchState = AtomicReference(FetchState.IDLE)

    // 请求世代ID，用于打断旧的递归任务
    private val fetchGeneration = AtomicLong(0)
    private val logTag = "FavoriteVM"
    private var allFavorites: List<Favorite> = listOf()

    // 预加载的表单校验码
    private var prefetchFormHash: String? = null

    // 记录最后一次成功触发刷新的时间戳
    private var lastSmartSyncTime = 0L

    // 冷却时间，5秒内不重复发起后台同步
    private val SMART_SYNC_COOLDOWN = 5_000L

    // 等待队列：保存正在倒计时的那个任务
    private var pendingSyncJob: Job? = null
    private var fetchJob: Job? = null
    private var lastNavigateTime = 0L
    private val SMART_SYNC_TIMEOUT = 10 * 60 * 1000L
    // 记录正在删除过程中的URL
    private val pendingDeleteUrls = mutableSetOf<String>()
    enum class RefreshStrategy {
        FULL,   // 全量刷新
        SMART,  // 增量刷新
        SKIP    // 跳过刷新
    }

    var nextResumeStrategy = RefreshStrategy.FULL
    var currentCategory: Int = -1
        private set
    var lastPauseTime = 0L
    var isFavoritePageVisible = false
    // 本地缓存工具
    private val localCache by lazy { LocalCacheUtil.getInstance(applicationContext) }

    init {
        viewModelScope.launch {
            FavoriteUtil.getFavoriteFlow()
                .flowOn(Dispatchers.IO)
                .collect { fullList ->
                    stateMutex.withLock {
                        allFavorites = fullList
                        updateUiList()
                    }
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
                refreshCacheInfo(index)
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
                if (pendingSyncJob?.isActive == true) return
                val waitTime = SMART_SYNC_COOLDOWN - timeSinceLast
                pendingSyncJob = viewModelScope.launch {
                    delay(waitTime)
                    lastSmartSyncTime = System.currentTimeMillis()
                    executeActualRefresh(showLoading, isSmartSync = true)
                }
                return
            } else {
                lastSmartSyncTime = currentTime
            }
        } else {
            pendingSyncJob?.cancel()
            lastSmartSyncTime = System.currentTimeMillis()
        }

        executeActualRefresh(showLoading, isSmartSync)
    }

    private fun executeActualRefresh(showLoading: Boolean, isSmartSync: Boolean) {
        val requestedState = if (isSmartSync) FetchState.BACKGROUND else FetchState.MANUAL

        while (true) {
            val currentState = currentFetchState.get()
            if (currentState == requestedState || currentState == FetchState.MANUAL) return
            if (currentFetchState.compareAndSet(currentState, requestedState)) break
        }

        val currentGen = fetchGeneration.incrementAndGet()

        if (showLoading) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }

        CookieUtil.getCookie {
            fetchJob?.cancel()
            fetchJob = viewModelScope.launch(Dispatchers.IO) {
                fetchAllFavoritesSuspend(
                    isSmartSync = isSmartSync,
                    isBackground = isSmartSync,
                    generation = currentGen
                )
            }
        }
    }

    private fun releaseStateIfCurrent(generation: Long) {
        if (fetchGeneration.get() == generation) {
            currentFetchState.set(FetchState.IDLE)
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    private suspend fun fetchAllFavoritesSuspend(
        isSmartSync: Boolean,
        isBackground: Boolean,
        generation: Long
    ) {
        val favoriteApi = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)
        var currentPage = 1
        var currentTotalPages = 1
        var currentIsSmartSync = isSmartSync
        val accumulatedList = ArrayList<Favorite>()
        var isRetry = false

        while (currentCoroutineContext().isActive && generation == fetchGeneration.get()) {
            try {
                val response = favoriteApi.getFavoritePage(currentPage).execute()

                if (generation != fetchGeneration.get()) break

                if (!response.isSuccessful) {
                    throw Exception("Network Failed")
                }

                val respHTML = response.body()?.string()
                val pageList = mutableListOf<Favorite>()

                if (respHTML != null) {
                    val parse = Jsoup.parse(respHTML)
                    val favList = parse.getElementsByClass("sclist")

                    favList.forEach { li ->
                        val aTag = li.select("a").last()

                        // 提取包含 favid 的删除标签
                        val delTag = li.selectFirst("a.mdel")
                        var extractedFavId: String? = null
                        if (delTag != null) {
                            val delHref = delTag.attr("href")
                            val matchResult = Regex("favid=(\\d+)").find(delHref)
                            if (matchResult != null) {
                                extractedFavId = matchResult.groupValues[1]
                            }
                        }

                        if (aTag != null) {
                            val favorite = Favorite(aTag.text(), aTag.attr("href"))
                            favorite.favId = extractedFavId // 注入获取到的ID
                            pageList.add(favorite)
                        }
                    }
                }

                if (pageList.isNotEmpty()) {
                    val safePageList = stateMutex.withLock {
                        pageList.filterNot { pendingDeleteUrls.contains(it.url) }
                    }

                    accumulatedList.addAll(safePageList)
                    val hasNewItems = FavoriteUtil.mergeFavoritesProgressiveSuspend(safePageList)

                    if (generation != fetchGeneration.get()) break

                    if (currentPage == 1) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(isRefreshing = false)
                        }

                        val parse = Jsoup.parse(respHTML!!)
                        val nextPageLink = parse.select(".page a, .pg a").find {
                            it.text().contains("下一页") || it.hasClass("nxt")
                        }
                        val hasNextPage = nextPageLink != null && nextPageLink.attr("href").isNotBlank()

                        currentTotalPages = MangaHtmlParser.extractTotalPages(respHTML)
                        val maxPossibleRemoteItems = currentTotalPages * 20

                        stateMutex.withLock {
                            if (allFavorites.size > maxPossibleRemoteItems) {
                                currentIsSmartSync = false
                            } else if (!hasNextPage) {
                                currentIsSmartSync = false
                            }
                        }
                    }

                    val parse = Jsoup.parse(respHTML!!)
                    val nextPageLink = parse.select(".page a, .pg a").find {
                        it.text().contains("下一页") || it.hasClass("nxt")
                    }
                    val hasNextPage = nextPageLink != null && nextPageLink.attr("href").isNotBlank()

                    val shouldContinue = if (currentIsSmartSync) {
                        hasNewItems && hasNextPage
                    } else {
                        hasNextPage
                    }

                    if (shouldContinue) {
                        val dynamicDelay = if (isBackground) {
                            (1200L - ((currentTotalPages - 1) * 300L)).coerceIn(600L, 1000L)
                        } else 300L
                        delay(dynamicDelay)
                        currentPage++
                        isRetry = false
                    } else {
                        if (!currentIsSmartSync) {
                            FavoriteUtil.cleanupDeletedFavoritesSuspend(accumulatedList)
                        }
                        break
                    }

                } else {
                    if (currentPage == 1) {
                        val htmlStr = respHTML ?: ""
                        val isRealEmptyPage = htmlStr.contains("您还没有添加任何收藏")

                        if (isRealEmptyPage) {
                            FavoriteUtil.cleanupDeletedFavoritesSuspend(emptyList())
                            break
                        } else {
                            val isLoggedIn = GlobalData.currentCookie.contains("EeqY_2132_auth=")

                            if (!isLoggedIn) {
                                if (isFavoritePageVisible) {
                                    withContext(Dispatchers.Main) {
                                        val toast = Toast.makeText(applicationContext, "登录状态异常", Toast.LENGTH_SHORT)
                                        toast.show()
                                        launch { delay(1500L); toast.cancel() }
                                    }
                                }
                                break
                            }

                            if (!isRetry) {
                                delay(500L)
                                isRetry = true
                                continue
                            } else {
                                if (isFavoritePageVisible) {
                                    withContext(Dispatchers.Main) {
                                        val toast = Toast.makeText(applicationContext, "网络状态异常", Toast.LENGTH_SHORT)
                                        toast.show()
                                        launch { delay(1500L); toast.cancel() }
                                    }
                                }
                                break
                            }
                        }
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                break
            }
        }
        releaseStateIfCurrent(generation)
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
    fun updateStrategyBeforeNavigation(type: Int) {
        lastNavigateTime = System.currentTimeMillis()

        nextResumeStrategy = when (type) {
            1 -> RefreshStrategy.SKIP
            2 -> RefreshStrategy.SKIP
            else -> RefreshStrategy.SMART
        }
    }
    fun updateMangaProgress(favoriteUrl: String, chapterUrl: String, chapterTitle: String, pageIndex: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val updated = allFavorites.map { fav ->
                    if (fav.url == favoriteUrl) {
                        val updatedFav = fav.copy(lastMangaUrl = chapterUrl, lastChapter = chapterTitle, lastPage = pageIndex)
                        launch { FavoriteUtil.updateFavoriteSuspend(updatedFav) }
                        updatedFav
                    } else fav
                }
                allFavorites = updated
            }
            withContext(Dispatchers.Main) { updateUiList() }
        }
    }
    fun moveFavorite(from: Int, to: Int) {
        if (_uiState.value.isInManageMode) return

        val currentUiList = _uiState.value.favoriteList.toMutableList()
        if (from < 0 || from >= currentUiList.size || to < 0 || to >= currentUiList.size || from == to) return

        val item = currentUiList.removeAt(from)
        currentUiList.add(to, item)

        _uiState.value = _uiState.value.copy(favoriteList = currentUiList.toList())

        viewModelScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val categoryUrls = currentUiList.map { it.url }.toSet()
                val newQueue = java.util.LinkedList(currentUiList)

                val newListToSave = allFavorites.map { fav ->
                    if (categoryUrls.contains(fav.url)) newQueue.poll() ?: fav else fav
                }
                allFavorites = newListToSave
                FavoriteUtil.saveFavoriteOrder(newListToSave)
            }
        }
    }

    fun toggleManageMode() {
        val newMode = !_uiState.value.isInManageMode
        _uiState.value = _uiState.value.copy(
            isInManageMode = newMode,
            selectedItems = emptySet()
        )
        updateUiList()

        // 进入管理模式时，开启后台探针
        if (newMode) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val api = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)
                    val faqResponse = api.getFormHash().execute()
                    val html = faqResponse.body()?.string() ?: return@launch
                    val match = Regex("""formhash=([a-zA-Z0-9]{8})""").find(html)
                    prefetchFormHash = match?.groupValues?.get(1)
                } catch (_: Exception) {
                    // 静默失败
                }
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.updateHiddenStatus(itemsToHide, true)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(selectedItems = emptySet())
            }
        }
    }

    fun unhideSelectedItems() {
        val itemsToUnhide = _uiState.value.selectedItems
        if (itemsToUnhide.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.updateHiddenStatus(itemsToUnhide, false)

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(selectedItems = emptySet())
            }
        }
    }


    fun deleteSelectedFavorites(onToast: (String) -> Unit) {
        val itemsToDeleteUrls = _uiState.value.selectedItems.toSet()
        if (itemsToDeleteUrls.isEmpty()) return

        val favIdsToDelete = allFavorites
            .filter { itemsToDeleteUrls.contains(it.url) && !it.favId.isNullOrEmpty() }
            .map { it.favId!! }

        if (favIdsToDelete.isEmpty()) {
            onToast("数据缺失，请下拉刷新获取最新列表！")
            return
        }

        val backupList = allFavorites

        // 刷新UI
        viewModelScope.launch(Dispatchers.Main) {
            stateMutex.withLock {
                pendingDeleteUrls.addAll(itemsToDeleteUrls)
                val updatedList = allFavorites.filterNot { itemsToDeleteUrls.contains(it.url) }
                allFavorites = updatedList
                FavoriteUtil.saveFavoriteOrder(updatedList)
            }
            _uiState.value = _uiState.value.copy(selectedItems = emptySet(), isInManageMode = false)
            updateUiList()
        }

        // 后台请求删除 + 自动清理本地缓存
        viewModelScope.launch(Dispatchers.IO) {
            val isSuccess = FavoriteDeleteUtil.deleteFavoritesBatch(prefetchFormHash, favIdsToDelete)
            stateMutex.withLock {
                // 无论成功还是失败，网络请求结束，移除黑名单
                pendingDeleteUrls.removeAll(itemsToDeleteUrls)

                if (!isSuccess) {
                    // 失败了，回滚数据
                    allFavorites = backupList
                    FavoriteUtil.saveFavoriteOrder(backupList)
                }
            }
            if (isSuccess) {
                itemsToDeleteUrls.forEach { url ->
                    try { localCache.deleteNovel(url) } catch (e: Exception) {}
                }
                refreshCacheInfo()
                withContext(Dispatchers.Main) { onToast("删除成功") }
            } else {
                stateMutex.withLock {
                    allFavorites = backupList
                    FavoriteUtil.saveFavoriteOrder(backupList)
                }
                withContext(Dispatchers.Main) {
                    updateUiList()
                    onToast("网络异常，删除失败")
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
                    cacheInfoMap[url] =
                        CacheInfo(url, totalPages, totalSize, pagesWithImages, novelCache.title)
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
            stateMutex.withLock {
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
            }
            withContext(Dispatchers.Main) { updateUiList() }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val updated = allFavorites.map { fav ->
                    fav.copy(lastPage = 0, lastView = 1, lastChapter = null, lastMangaUrl = null)
                }
                allFavorites = updated
                FavoriteUtil.saveFavoriteOrder(updated)
            }
            withContext(Dispatchers.Main) { updateUiList() }
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
    fun moveToTop(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.moveUrlToTopSuspend(url)
        }
    }
}