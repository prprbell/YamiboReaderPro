package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.fastjson2.JSON
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
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteDeleteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.TombstoneQueueUtil
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
    private val SMART_SYNC_TIMEOUT = 20 * 60 * 1000L

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
            TombstoneQueueUtil.initQueue()
            retryPendingDeletesQuietly()

            FavoriteUtil.getFavoriteFlow()
                .flowOn(Dispatchers.IO)
                .collect { fullList ->
                    stateMutex.withLock {
                        allFavorites = fullList
                        updateUiList()
                    }
                    refreshCacheInfo(localCache.index.value)
                    val titleMap = fullList.associate {
                        val cleanTitle = it.title.replace(Regex("^(?:【.*?】|\\[.*?\\]|\\s)+"), "")
                            .ifBlank { it.title }
                        it.url to cleanTitle
                    }
                    localCache.updateCacheTitlesCompat(
                        titlesMap = titleMap,
                        normalizeUrl = { FavoriteUtil.normalizeUrl(it) }
                    )
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

        val pendingFiltered = if (pendingDeleteUrls.isNotEmpty()) {
            baseList.filter { it.url !in pendingDeleteUrls }
        } else {
            baseList
        }

        val filteredList = if (currentCategory == -1) {
            pendingFiltered
        } else {
            pendingFiltered.filter { it.type == currentCategory }
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

        while (currentCoroutineContext().isActive && generation == fetchGeneration.get()) {
            try {
                val resp = favoriteApi.getMyFavThread(currentPage).string()
                if (generation != fetchGeneration.get()) break

                val json = JSON.parseObject(resp)
                val variables = json.getJSONObject("Variables")
                    ?: throw Exception("Missing Variables")
                prefetchFormHash = variables.getString("formhash") ?: prefetchFormHash
                val list = variables.getJSONArray("list")
                val pageList = mutableListOf<Favorite>()

                if (list != null && list.isNotEmpty()) {
                    for (i in 0 until list.size) {
                        val item = list.getJSONObject(i) ?: continue
                        val favId = item.getString("favid") ?: ""
                        val title = item.getString("title") ?: ""
                        val url = FavoriteUtil.normalizeUrl(item.getString("url") ?: "")

                        val favorite = Favorite(title, url)
                        favorite.favId = favId
                        pageList.add(favorite)
                    }
                }

                if (pageList.isNotEmpty()) {
                    val pendingUrls = TombstoneQueueUtil.getPendingUrls()
                    val safePageList = stateMutex.withLock {
                        pageList.filterNot { pendingUrls.contains(it.url) }
                    }

                    accumulatedList.addAll(safePageList)
                    val hasNewItems = FavoriteUtil.mergeFavoritesProgressiveSuspend(safePageList)

                    if (generation != fetchGeneration.get()) break

                    if (currentPage == 1) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(isRefreshing = false)
                        }

                        val count = variables.getString("count")?.toIntOrNull() ?: 0
                        val perpage = variables.getString("perpage")?.toIntOrNull() ?: 20
                        currentTotalPages = if (count > 0) {
                            (count + perpage - 1) / perpage
                        } else 1
                        val hasNextPage = currentPage < currentTotalPages
                        val maxPossibleRemoteItems = count

                        stateMutex.withLock {
                            if (allFavorites.size > maxPossibleRemoteItems) {
                                currentIsSmartSync = false
                            } else if (!hasNextPage) {
                                currentIsSmartSync = false
                            }
                        }
                    }

                    val hasNextPage = currentPage < currentTotalPages

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
                    } else {
                        if (!currentIsSmartSync) {
                            FavoriteUtil.cleanupDeletedFavoritesSuspend(accumulatedList)
                        }
                        break
                    }
                } else {
                    if (currentPage == 1) {
                        val isLoggedIn = !variables.getString("auth").isNullOrBlank()

                        if (!isLoggedIn) {
                            if (isFavoritePageVisible) {
                                withContext(Dispatchers.Main) {
                                    val toast = Toast.makeText(
                                        applicationContext,
                                        "登录状态异常",
                                        Toast.LENGTH_SHORT
                                    )
                                    toast.show()
                                    launch { delay(1500L); toast.cancel() }
                                }
                            }
                            break
                        }

                        // 只有服务端明确返回 count=0 才确认是空收藏，否则拒绝清理
                        val count = variables.getString("count")?.toIntOrNull() ?: -1
                        if (count == 0) {
                            FavoriteUtil.cleanupDeletedFavoritesSuspend(emptyList())
                        } else if (isFavoritePageVisible) {
                            withContext(Dispatchers.Main) {
                                val toast = Toast.makeText(
                                    applicationContext,
                                    "网络状态异常",
                                    Toast.LENGTH_SHORT
                                )
                                toast.show()
                                launch { delay(1500L); toast.cancel() }
                            }
                        }
                        break
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

    fun updateMangaProgress(
        favoriteUrl: String,
        chapterUrl: String,
        chapterTitle: String,
        pageIndex: Int = 0
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val updated = allFavorites.map { fav ->
                    if (fav.url == favoriteUrl) {
                        val updatedFav = fav.copy(
                            lastMangaUrl = chapterUrl,
                            lastChapter = chapterTitle,
                            lastPage = pageIndex
                        )
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
        val realCookie =
            android.webkit.CookieManager.getInstance().getCookie("https://bbs.yamibo.com") ?: ""
        val isLoggedIn = realCookie.contains("EeqY_2132_auth=")

        if (!isLoggedIn) return
        val newMode = !_uiState.value.isInManageMode
        _uiState.value = _uiState.value.copy(
            isInManageMode = newMode,
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

        val itemsToTombstone = allFavorites.filter {
            itemsToDeleteUrls.contains(it.url) && !it.favId.isNullOrEmpty()
        }

        if (itemsToTombstone.isEmpty()) {
            onToast("数据缺失 请刷新")
            return
        }

        val favIdsToDelete = itemsToTombstone.map { it.favId!! }
        val backupList = allFavorites

        viewModelScope.launch(Dispatchers.Main) {
            stateMutex.withLock {
                TombstoneQueueUtil.addItems(itemsToTombstone)

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
            val isSuccess =
                FavoriteDeleteUtil.deleteFavoritesBatch(prefetchFormHash, favIdsToDelete)
            stateMutex.withLock {
                // 无论成功还是失败，网络请求结束，移除黑名单
                pendingDeleteUrls.removeAll(itemsToDeleteUrls)

                if (!isSuccess) {
                    // 失败，回滚数据
                    allFavorites = backupList
                    FavoriteUtil.saveFavoriteOrder(backupList)
                }
            }
            if (isSuccess) {
                TombstoneQueueUtil.removeUrls(itemsToDeleteUrls)
                itemsToDeleteUrls.forEach { url ->
                    try {
                        val normalizedUrl = FavoriteUtil.normalizeUrl(url)

                        localCache.deleteNovelCompat(
                            primaryUrl = normalizedUrl,
                            aliasUrls = cacheAliasesForNormalizedUrl(normalizedUrl, url)
                        )
                    } catch (e: Exception) {
                    }
                }
                refreshCacheInfo()
            } else {
                stateMutex.withLock {
                    TombstoneQueueUtil.removeUrls(itemsToDeleteUrls)
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

            index.forEach { (rawUrl, novelCache) ->
                if (novelCache.pages.isEmpty()) return@forEach

                val normalizedUrl = FavoriteUtil.normalizeUrl(rawUrl)
                val totalPages = novelCache.pages.size
                val totalSize = novelCache.pages.values.sumOf { it.fileSize }
                val pagesWithImages = novelCache.pages.values.count { it.hasImages }

                val old = cacheInfoMap[normalizedUrl]
                cacheInfoMap[normalizedUrl] = if (old == null) {
                    CacheInfo(
                        url = normalizedUrl,
                        totalPages = totalPages,
                        totalSize = totalSize,
                        pagesWithImages = pagesWithImages,
                        title = novelCache.title
                    )
                } else {
                    old.copy(
                        totalPages = old.totalPages + totalPages,
                        totalSize = old.totalSize + totalSize,
                        pagesWithImages = old.pagesWithImages + pagesWithImages,
                        title = old.title ?: novelCache.title
                    )
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
                val normalizedUrl = FavoriteUtil.normalizeUrl(url)

                localCache.deleteNovelCompat(
                    primaryUrl = normalizedUrl,
                    aliasUrls = cacheAliasesForNormalizedUrl(normalizedUrl, url)
                )

                refreshCacheInfo()
            } catch (e: Exception) {
                Log.e(logTag, "删除 $url 的缓存失败", e)
            }
        }
    }

    private fun cacheAliasesForNormalizedUrl(normalizedUrl: String, originalUrl: String): List<String> {
        val absoluteUrl = org.shirakawatyu.yamibo.novel.util.reader.ReaderReturnBridge
            .toAbsoluteBbsUrl(normalizedUrl)

        val aliasesFromIndex = localCache.index.value.keys.filter { rawKey ->
            rawKey != normalizedUrl && FavoriteUtil.normalizeUrl(rawKey) == normalizedUrl
        }

        return buildList {
            add(originalUrl)
            add(absoluteUrl)
            addAll(aliasesFromIndex)
        }
            .map { it.trim() }
            .filter { it.isNotBlank() && it != normalizedUrl }
            .distinct()
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

    /**
     * 后台重试离线删除任务
     */
    private suspend fun retryPendingDeletesQuietly() {
        withContext(Dispatchers.IO) {
            val pendingEntries = TombstoneQueueUtil.getPendingUrls()
            if (pendingEntries.isEmpty()) return@withContext

            val favIdsToDelete = pendingEntries.map { it.substringAfter("|") }

            if (favIdsToDelete.isNotEmpty()) {
                val isSuccess = FavoriteDeleteUtil.deleteFavoritesBatch(null, favIdsToDelete)

                if (isSuccess) {
                    TombstoneQueueUtil.removeUrls(pendingEntries)
                }
            }
        }
    }
}