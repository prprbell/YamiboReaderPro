package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckStrategy
import org.shirakawatyu.yamibo.novel.bean.NovelUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.network.NovelApi
import org.shirakawatyu.yamibo.novel.repository.DirectoryRepository
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteDeleteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.TombstoneQueueUtil
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import org.shirakawatyu.yamibo.novel.util.updateCheck.MangaUpdateCheckUtil
import org.shirakawatyu.yamibo.novel.util.updateCheck.NovelUpdateCheckUtil
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class FetchState { IDLE, BACKGROUND, MANUAL }

class FavoriteVM(private val applicationContext: Context) : ViewModel() {
    companion object {
        private val updateCheckScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val novelMetaRequestMutex = Mutex()

        private const val NOVEL_META_REQUEST_INTERVAL_MS = 1_200L
        private var lastNovelMetaRequestStartedAt = 0L
    }

    private val _uiState = MutableStateFlow(FavoriteState())
    val uiState = _uiState.asStateFlow()
    private val stateMutex = Mutex()

    // 记录当前的刷新状态，默认为空闲
    private val currentFetchState = AtomicReference(FetchState.IDLE)

    // 请求世代ID，用于打断旧的递归任务
    private val fetchGeneration = AtomicLong(0)
    private val logTag = "FavoriteVM"
    private var allFavorites: List<Favorite> = listOf()
    private var updateCheckNovels: List<NovelUpdateCheckProfile> = listOf()
    private var updateCheckMangas: List<MangaUpdateCheckProfile> = listOf()

    // 预加载的表单校验码
    private var prefetchFormHash: String? = null

    // 记录最后一次成功触发刷新的时间戳
    private var lastSmartSyncTime = 0L

    // 冷却时间，5秒内不重复发起后台同步
    private val SMART_SYNC_COOLDOWN = 5_000L

    // 等待队列：保存正在倒计时的那个任务
    private var pendingSyncJob: Job? = null
    private var fetchJob: Job? = null
    private val updateCheckMutationMutex = Mutex()
    private var lastNavigateTime = 0L
    private val SMART_SYNC_TIMEOUT = 20 * 60 * 1000L

    private val UPDATE_CHECK_INTERVAL = 60L * 60L * 1000L

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

        viewModelScope.launch {
            NovelUpdateCheckUtil.getUpdateCheckFlow()
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    stateMutex.withLock {
                        updateCheckNovels = list
                    }
                    _uiState.value = _uiState.value.copy(updateCheckNovels = list)
                }
        }

        viewModelScope.launch {
            MangaUpdateCheckUtil.getUpdateCheckFlow()
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    stateMutex.withLock {
                        updateCheckMangas = list
                    }
                    _uiState.value = _uiState.value.copy(updateCheckMangas = list)
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

    private fun extractTid(url: String): String? = Regex("tid=(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun showShortToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isCheckTooFrequent(lastCheckTime: Long, now: Long): Boolean {
        return lastCheckTime > 0L && now - lastCheckTime < UPDATE_CHECK_INTERVAL
    }

    private fun remainingCheckCooldownText(lastCheckTime: Long, now: Long): String {
        val remaining = (UPDATE_CHECK_INTERVAL - (now - lastCheckTime)).coerceAtLeast(0L)
        val minutes = ((remaining + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "1小时内已检查过，约 ${minutes} 分钟后再试"
    }

    private fun setUpdateChecking(url: String, checking: Boolean) {
        val current = _uiState.value.checkingUpdateUrls
        val next = if (checking) current + url else current - url
        if (next != current) {
            _uiState.value = _uiState.value.copy(checkingUpdateUrls = next)
        }
    }

    private fun isUpdateChecking(url: String): Boolean =
        _uiState.value.checkingUpdateUrls.contains(url)

    private suspend fun fetchNovelRepliesQueued(tid: String, authorId: String): Int? {
        return novelMetaRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastNovelMetaRequestStartedAt
            if (elapsed in 0L..<NOVEL_META_REQUEST_INTERVAL_MS) {
                delay(NOVEL_META_REQUEST_INTERVAL_MS - elapsed)
            }
            lastNovelMetaRequestStartedAt = System.currentTimeMillis()

            val novelApi = YamiboRetrofit.getInstance().create(NovelApi::class.java)
            val resp = novelApi.getThreadMeta(tid, authorId).string()
            val json = JSON.parseObject(resp)
            val thread = json.getJSONObject("Variables")?.getJSONObject("thread")
            thread?.getString("replies")?.toIntOrNull()
        }
    }

    fun checkFavoriteUpdate(
        favorite: Favorite,
        mangaStrategy: MangaUpdateCheckStrategy? = null,
        mangaSearchKeyword: String? = null
    ) {
        when (favorite.type) {
            1 -> checkNovelUpdate(favorite)
            2 -> checkMangaUpdate(favorite, mangaStrategy, mangaSearchKeyword)
            else -> showUnsupportedUpdateCheckType()
        }
    }

    fun checkNovelUpdate(favorite: Favorite) {
        if (favorite.type != 1) {
            showUnsupportedUpdateCheckType()
            return
        }
        if (isUpdateChecking(favorite.url)) {
            viewModelScope.launch { showShortToast("正在查询更新") }
            return
        }
        setUpdateChecking(favorite.url, true)
        updateCheckScope.launch {
            try {
                runNovelUpdateCheck(favorite)
            } finally {
                setUpdateChecking(favorite.url, false)
            }
        }
    }

    private suspend fun runNovelUpdateCheck(favorite: Favorite) {
        val tid = extractTid(favorite.url)
        if (tid == null) {
            showShortToast("无法识别帖子ID，不能查询更新")
            return
        }
        val authorId = favorite.authorId
        if (authorId.isNullOrBlank()) {
            showShortToast("缺少作者ID，不能查询小说更新")
            return
        }

        updateCheckMutationMutex.withLock {
            val profile = NovelUpdateCheckUtil.getMapSuspend()[favorite.url]
            val now = System.currentTimeMillis()
            if (profile != null && isCheckTooFrequent(profile.lastCheckTime, now)) {
                showShortToast(remainingCheckCooldownText(profile.lastCheckTime, now))
                return@withLock
            }

            try {
                val currentReplies = fetchNovelRepliesQueued(tid, authorId)
                if (currentReplies == null) {
                    showShortToast("查询失败：没有读取到回复数")
                    profile?.let { NovelUpdateCheckUtil.updateCheckTimeSuspend(favorite.url, System.currentTimeMillis()) }
                    return@withLock
                }

                val checkedAt = System.currentTimeMillis()
                if (profile == null) {
                    NovelUpdateCheckUtil.saveProfileSuspend(
                        NovelUpdateCheckProfile(
                            title = favorite.title,
                            url = favorite.url,
                            authorId = authorId,
                            savedReplies = currentReplies,
                            hasUpdate = false,
                            lastCheckTime = checkedAt
                        )
                    )
                    return@withLock
                }

                val detectedUpdate = currentReplies > profile.savedReplies
                NovelUpdateCheckUtil.updateRepliesSuspend(
                    url = favorite.url,
                    newReplies = currentReplies,
                    hasUpdate = detectedUpdate,
                    lastCheckTime = checkedAt
                )
                showShortToast(if (detectedUpdate) "检测到小说更新" else "没有检测到小说更新")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                profile?.let { NovelUpdateCheckUtil.updateCheckTimeSuspend(favorite.url, System.currentTimeMillis()) }
                showShortToast("查询小说更新失败")
            }
        }
    }

    fun clearNovelUpdateCheckFlag(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NovelUpdateCheckUtil.clearUpdateFlagSuspend(url)
        }
    }

    fun checkMangaUpdate(
        favorite: Favorite,
        overrideStrategy: MangaUpdateCheckStrategy? = null,
        overrideSearchKeyword: String? = null,
        overrideCleanBookName: String? = null
    ) {
        if (favorite.type != 2) {
            showUnsupportedUpdateCheckType()
            return
        }
        if (isUpdateChecking(favorite.url)) {
            viewModelScope.launch { showShortToast("正在查询更新") }
            return
        }
        setUpdateChecking(favorite.url, true)
        updateCheckScope.launch {
            try {
                runMangaUpdateCheck(favorite, overrideStrategy, overrideSearchKeyword, overrideCleanBookName)
            } finally {
                setUpdateChecking(favorite.url, false)
            }
        }
    }

    private suspend fun runMangaUpdateCheck(
        favorite: Favorite,
        overrideStrategy: MangaUpdateCheckStrategy? = null,
        overrideSearchKeyword: String? = null,
        overrideCleanBookName: String? = null
    ) {
        val tid = extractTid(favorite.url)
        if (tid == null) {
            showShortToast("无法识别帖子ID，不能查询更新")
            return
        }

        updateCheckMutationMutex.withLock {
            val repo = DirectoryRepository.getInstance(applicationContext)
            val oldProfile = MangaUpdateCheckUtil.getMapSuspend()[favorite.url]
            val now = System.currentTimeMillis()
            if (oldProfile != null && isCheckTooFrequent(oldProfile.lastCheckTime, now)) {
                showShortToast(remainingCheckCooldownText(oldProfile.lastCheckTime, now))
                return@withLock
            }

            try {
                val allDirs = repo.getAllDirectories()
                var mangaDir = if (oldProfile != null) {
                    allDirs.find { it.cleanBookName == oldProfile.cleanBookName }
                } else {
                    allDirs.find { dir -> dir.chapters.any { it.tid == tid } }
                }

                if (mangaDir == null) {
                    val mangaApi = YamiboRetrofit.getInstance().create(MangaApi::class.java)
                    val resp = mangaApi.getThreadDetailApi(tid).string()
                    val json = JSON.parseObject(resp)
                    val postlist = json.getJSONObject("Variables")?.getJSONArray("postlist")
                    val message = postlist?.getJSONObject(0)?.getString("message")
                    if (message.isNullOrBlank()) {
                        showShortToast("初始化漫画目录失败")
                        return@withLock
                    }
                    val html = "<div class=\"message\">$message</div>"
                    mangaDir = repo.initDirectoryForThread(tid, favorite.url, favorite.title, html)
                }

                // 如果用户修改了漫画名称，合并/重命名目录
                if (overrideCleanBookName != null && overrideCleanBookName.isNotBlank() && overrideCleanBookName != mangaDir.cleanBookName) {
                    val newKeyword = overrideSearchKeyword ?: mangaDir.searchKeyword ?: ""
                    mangaDir = repo.renameAndMergeDirectory(mangaDir, overrideCleanBookName, newKeyword)
                }

                val strategy = overrideStrategy
                    ?: oldProfile?.strategy
                    ?: if (mangaDir.strategy == org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy.TAG)
                        MangaUpdateCheckStrategy.TAG else MangaUpdateCheckStrategy.SEARCH

                val keyword = overrideSearchKeyword ?: oldProfile?.searchKeyword ?: mangaDir.searchKeyword
                val cleanBookName = overrideCleanBookName ?: oldProfile?.cleanBookName ?: mangaDir.cleanBookName
                val baseChapterCount = oldProfile?.savedChapterCount ?: mangaDir.chapters.size
                val baseLatestTid = oldProfile?.savedLatestTid ?: (mangaDir.chapters.lastOrNull()?.tid ?: "")

                val isFirstCheck = oldProfile == null
                if (isFirstCheck) {
                    MangaUpdateCheckUtil.saveProfileSuspend(
                        MangaUpdateCheckProfile(
                            title = favorite.title,
                            url = favorite.url,
                            cleanBookName = cleanBookName,
                            searchKeyword = keyword,
                            strategy = strategy,
                            savedChapterCount = baseChapterCount,
                            savedLatestTid = baseLatestTid,
                            hasUpdate = false,
                            lastCheckTime = 0L
                        )
                    )
                } else {
                    val existing = oldProfile!!
                    if (existing.searchKeyword != keyword || existing.strategy != strategy) {
                        MangaUpdateCheckUtil.saveProfileSuspend(
                            existing.copy(
                                title = favorite.title,
                                searchKeyword = keyword,
                                strategy = strategy
                            )
                        )
                    }
                }

                val dirForUpdate = if (keyword != mangaDir.searchKeyword) {
                    mangaDir.copy(searchKeyword = keyword)
                } else mangaDir

                val forceSearch = strategy == MangaUpdateCheckStrategy.SEARCH
                val result = repo.manuallyUpdateDirectory(dirForUpdate, forceSearch = forceSearch, currentTid = tid)
                if (result.isFailure) {
                    MangaUpdateCheckUtil.updateCheckTimeSuspend(favorite.url, System.currentTimeMillis())
                    val errorMessage = result.exceptionOrNull()?.message ?: "未知错误"
                    showShortToast("查询漫画更新失败：$errorMessage")
                    return@withLock
                }

                val updatedDir = result.getOrThrow().directory
                val newCount = updatedDir.chapters.size
                val newLatestTid = updatedDir.chapters.lastOrNull()?.tid ?: ""
                val snapshotCount = if (newCount > 0) newCount else baseChapterCount
                val snapshotLatestTid = newLatestTid.ifEmpty { baseLatestTid }
                val detectedUpdate = newCount > baseChapterCount ||
                        (newLatestTid.isNotEmpty() && baseLatestTid.isNotEmpty() && newLatestTid != baseLatestTid)

                MangaUpdateCheckUtil.updateSnapshotSuspend(
                    url = favorite.url,
                    chapterCount = snapshotCount,
                    latestTid = snapshotLatestTid,
                    hasUpdate = detectedUpdate,
                    lastCheckTime = System.currentTimeMillis(),
                    searchKeyword = keyword,
                    strategy = strategy
                )
                if (!isFirstCheck) {
                    showShortToast(if (detectedUpdate) "检测到漫画更新" else "没有检测到漫画更新")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                MangaUpdateCheckUtil.getMapSuspend()[favorite.url]?.let {
                    MangaUpdateCheckUtil.updateCheckTimeSuspend(favorite.url, System.currentTimeMillis())
                }
                showShortToast("查询漫画更新失败")
            }
        }
    }

    fun clearMangaUpdateCheckFlag(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MangaUpdateCheckUtil.clearUpdateFlagSuspend(url)
        }
    }

    fun showUnsupportedUpdateCheckType() {
        viewModelScope.launch {
            showShortToast("只有小说和漫画可以查询更新")
        }
    }

    fun getSearchCooldownRemainingMs(): Long {
        val elapsed = System.currentTimeMillis() - org.shirakawatyu.yamibo.novel.global.GlobalData.lastSearchTimestamp.get()
        val remaining = 20_000L - elapsed
        return remaining.coerceAtLeast(0L)
    }

}
