package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.update
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
import org.shirakawatyu.yamibo.novel.network.NovelApi
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteDeleteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.favorite.TombstoneQueueUtil
import org.shirakawatyu.yamibo.novel.util.reader.LocalCacheUtil
import org.shirakawatyu.yamibo.novel.util.updateCheck.AutoUpdateCheckScheduler
import org.shirakawatyu.yamibo.novel.util.updateCheck.MangaUpdateCheckUtil
import org.shirakawatyu.yamibo.novel.util.updateCheck.NovelUpdateCheckUtil
import org.shirakawatyu.yamibo.novel.util.updateCheck.UpdateCheckEngine
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class FetchState { IDLE, BACKGROUND, MANUAL }

class FavoriteVM(private val applicationContext: Context) : ViewModel() {
    companion object {
        /** 自动检查同时启用的总上限（小说 + 漫画合计）。 */
        const val MAX_AUTO_CHECK = 16

        /** 自动检查允许的间隔：避免过长间隔导致错过更新。 */
        val AUTO_CHECK_INTERVALS = listOf(3, 6, 12, 24, 72)

        /** 手动检查小说很快结束时，至少让加载圈稳定可见一小段时间，避免闪烁。 */
        private const val MIN_UPDATE_CHECK_VISIBLE_MS = 650L

        /** 类型探测通常也很快，至少让右侧胶囊稳定可见一小段时间。 */
        private const val MIN_TYPE_PROBE_VISIBLE_MS = 650L

        private val MANGA_SECTIONS = listOf("中文百合漫画区", "贴图区", "貼圖區", "原创图作区", "百合漫画图源区")
        private val NOVEL_SECTIONS = listOf("文學區", "文学区", "轻小说/译文区")
        private val FORCED_OTHER_SECTIONS = listOf("TXT小说区")
        private val MANGA_FIDS = setOf("13", "30", "46")
        private val NOVEL_FIDS = setOf("49", "55")
        private val FORCED_OTHER_FIDS = setOf("60")
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
    private val updateCheckVisibleSince = mutableMapOf<String, Long>()
    private val updateCheckHideJobs = mutableMapOf<String, Job>()
    private val typeProbeJobs = mutableMapOf<String, Job>()
    private val typeProbeJobsLock = Any()
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

        viewModelScope.launch {
            NovelUpdateCheckUtil.getUpdateCheckFlow()
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    stateMutex.withLock {
                        updateCheckNovels = list
                    }
                    _uiState.update { it.copy(updateCheckNovels = list) }
                }
        }

        viewModelScope.launch {
            MangaUpdateCheckUtil.getUpdateCheckFlow()
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    stateMutex.withLock {
                        updateCheckMangas = list
                    }
                    _uiState.update { it.copy(updateCheckMangas = list) }
                }
        }

        // 初始化检查引擎（进程级），并把"正在检查"的集合镜像到 UI 状态。
        // UI 层保留最短可见时长，避免小说检查过快导致加载圈闪一下就消失。
        UpdateCheckEngine.ensureInit(applicationContext)
        viewModelScope.launch {
            UpdateCheckEngine.inFlight.collect { set ->
                mirrorUpdateCheckingUrls(set)
            }
        }
    }

    fun setCategory(category: Int) {
        currentCategory = category
        updateUiList()
    }

    private data class TypeProbeResult(
        val type: Int,
        val title: String,
        val authorId: String
    )

    /**
     * 后台探测未定收藏的类型。
     *
     * 只请求论坛线程 API 并更新本地收藏元数据，不打开 ProbingPage / WebView，
     * 因此左滑探测不会把用户从收藏页带走。
     */
    fun probeFavoriteTypeInBackground(favorite: Favorite) {
        if (favorite.type != 0) return
        val alreadyProbing = synchronized(typeProbeJobsLock) {
            typeProbeJobs[favorite.url]?.isActive == true
        }
        if (alreadyProbing) {
            viewModelScope.launch { showShortToast("正在探测类型") }
            return
        }

        val url = favorite.url
        val job = viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            markTypeProbeVisible(url)

            try {
                val result = probeFavoriteTypeSuspend(favorite)
                if (result == null) {
                    showShortToast("探测类型失败")
                    return@launch
                }

                val changed = applyTypeProbeResult(favorite, result)
                startUpdateTrackingAfterTypeProbe(favorite, result)
                val label = when (result.type) {
                    1 -> "小说"
                    2 -> "漫画"
                    else -> "其他"
                }
                showShortToast(if (changed) "已识别为$label" else "类型已是$label")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(logTag, "后台探测收藏类型失败: ${favorite.url}", e)
                showShortToast("探测类型失败")
            } finally {
                val elapsed = System.currentTimeMillis() - startedAt
                val remainMs = (MIN_TYPE_PROBE_VISIBLE_MS - elapsed).coerceAtLeast(0L)
                if (remainMs > 0) delay(remainMs)
                markTypeProbeHidden(url)
                synchronized(typeProbeJobsLock) { typeProbeJobs.remove(url) }
            }
        }
        synchronized(typeProbeJobsLock) { if (job.isActive) typeProbeJobs[url] = job }
    }

    private fun markTypeProbeVisible(url: String) {
        _uiState.update { it.copy(probingTypeUrls = it.probingTypeUrls + url) }
    }

    private fun markTypeProbeHidden(url: String) {
        _uiState.update { it.copy(probingTypeUrls = it.probingTypeUrls - url) }
    }

    private suspend fun probeFavoriteTypeSuspend(favorite: Favorite): TypeProbeResult? {
        val tid = Regex("tid=(\\d+)").find(favorite.url)?.groupValues?.get(1)
        if (tid == null) {
            return TypeProbeResult(type = 3, title = favorite.title, authorId = favorite.authorId ?: "")
        }

        val novelApi = YamiboRetrofit.getInstance().create(NovelApi::class.java)
        val resp = novelApi.getThreadMetaLight(tid).string()
        val json = JSON.parseObject(resp)
        val variables = json.getJSONObject("Variables") ?: return null
        val thread = variables.getJSONObject("thread") ?: return null
        val forumName = variables.getJSONObject("forum")?.getString("name") ?: ""
        val title = thread.getString("subject") ?: favorite.title
        val authorId = thread.getString("authorid") ?: ""
        val fid = thread.getString("fid") ?: ""

        val type = when {
            fid in FORCED_OTHER_FIDS || FORCED_OTHER_SECTIONS.any { forumName.contains(it) } -> 3
            fid in MANGA_FIDS || MANGA_SECTIONS.any { forumName.contains(it) } -> 2
            fid in NOVEL_FIDS || (forumName.isNotEmpty() && NOVEL_SECTIONS.any { forumName.contains(it) }) -> 1
            else -> 3
        }

        return TypeProbeResult(type = type, title = title, authorId = authorId)
    }

    private fun startUpdateTrackingAfterTypeProbe(favorite: Favorite, result: TypeProbeResult) {
        when (result.type) {
            1 -> {
                val cleanTitle = cleanNovelProbeTitle(result.title)
                UpdateCheckEngine.trackNovelSilently(
                    url = favorite.url,
                    title = cleanTitle.ifBlank { favorite.title },
                    authorId = result.authorId
                )
            }

            2 -> UpdateCheckEngine.trackMangaSilently(
                url = favorite.url,
                title = result.title.ifBlank { favorite.title }
            )
        }
    }

    private suspend fun applyTypeProbeResult(favorite: Favorite, result: TypeProbeResult): Boolean {
        var updatedFavorite: Favorite? = null

        stateMutex.withLock {
            val old = allFavorites.find { it.url == favorite.url } ?: favorite
            var next = old

            when (result.type) {
                1 -> {
                    val aid = result.authorId.takeIf { it.isNotBlank() }
                    next = next.copy(type = 1, authorId = aid)

                    val cleanTitle = cleanNovelProbeTitle(result.title)
                    if (cleanTitle.isNotBlank()) next = next.copy(title = cleanTitle)
                }

                2 -> next = next.copy(type = 2)
                else -> next = next.copy(type = 3)
            }

            if (next != old) {
                allFavorites = allFavorites.map { if (it.url == next.url) next else it }
                updatedFavorite = next
            }
        }

        updatedFavorite?.let { FavoriteUtil.updateFavoriteSuspend(it) }
        withContext(Dispatchers.Main) { updateUiList() }
        return updatedFavorite != null
    }

    private fun cleanNovelProbeTitle(rawTitle: String): String =
        rawTitle.replace(
            Regex("\\s+[-—–_]+\\s+.*?(文學區|文学区|小说区|译文区|百合会|论坛).*$"),
            ""
        ).trim().ifBlank { rawTitle }

    private fun mirrorUpdateCheckingUrls(actualCheckingUrls: Set<String>) {
        val now = System.currentTimeMillis()

        actualCheckingUrls.forEach { url ->
            updateCheckHideJobs.remove(url)?.cancel()
            updateCheckVisibleSince.putIfAbsent(url, now)
        }

        val currentVisible = _uiState.value.checkingUpdateUrls
        val visibleWithActive = currentVisible + actualCheckingUrls
        if (visibleWithActive != currentVisible) {
            _uiState.update { it.copy(checkingUpdateUrls = visibleWithActive) }
        }

        val removedUrls = visibleWithActive - actualCheckingUrls
        removedUrls.forEach { url ->
            val shownFor = now - (updateCheckVisibleSince[url] ?: now)
            val remainMs = (MIN_UPDATE_CHECK_VISIBLE_MS - shownFor).coerceAtLeast(0L)

            if (remainMs == 0L) {
                updateCheckVisibleSince.remove(url)
                updateCheckHideJobs.remove(url)?.cancel()
                _uiState.update { it.copy(checkingUpdateUrls = it.checkingUpdateUrls - url) }
            } else if (updateCheckHideJobs[url]?.isActive != true) {
                updateCheckHideJobs[url] = viewModelScope.launch {
                    delay(remainMs)
                    if (!UpdateCheckEngine.isChecking(url)) {
                        updateCheckVisibleSince.remove(url)
                        _uiState.update { it.copy(checkingUpdateUrls = it.checkingUpdateUrls - url) }
                    }
                    updateCheckHideJobs.remove(url)
                }
            }
        }
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

        _uiState.update { it.copy(favoriteList = filteredList) }
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
            _uiState.update { it.copy(isRefreshing = true) }
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
                _uiState.update { it.copy(isRefreshing = false) }
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
                            _uiState.update { it.copy(isRefreshing = false) }
                        }

                        val count = variables.getString("count")?.toIntOrNull() ?: 0
                        val perpage = variables.getString("perpage")?.toIntOrNull() ?: 20
                        currentTotalPages = if (count > 0) {
                            (count + perpage - 1) / perpage
                        } else 1
                        val hasNextPage = currentPage < currentTotalPages

                        stateMutex.withLock {
                            if (allFavorites.size > count) {
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
                                    YamiboToast.show(
                                        context = applicationContext,
                                        message = "登录状态异常",
                                        durationMillis = 1500L
                                    )
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
                                YamiboToast.show(
                                    context = applicationContext,
                                    message = "网络状态异常",
                                    durationMillis = 1500L
                                )
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

        _uiState.update { it.copy(favoriteList = currentUiList.toList()) }

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
        _uiState.update { it.copy(
            isInManageMode = newMode,
            selectedItems = emptySet()
        ) }
        updateUiList()
    }

    fun toggleItemSelection(url: String) {
        if (!_uiState.value.isInManageMode) return

        val newSelections = _uiState.value.selectedItems.toMutableSet()
        if (newSelections.contains(url)) newSelections.remove(url)
        else newSelections.add(url)

        _uiState.update { it.copy(selectedItems = newSelections) }
    }

    fun hideSelectedItems() {
        val itemsToHide = _uiState.value.selectedItems
        if (itemsToHide.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.updateHiddenStatus(itemsToHide, true)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectedItems = emptySet()) }
            }
        }
    }

    fun unhideSelectedItems() {
        val itemsToUnhide = _uiState.value.selectedItems
        if (itemsToUnhide.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.updateHiddenStatus(itemsToUnhide, false)

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectedItems = emptySet()) }
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
            _uiState.update { it.copy(selectedItems = emptySet(), isInManageMode = false) }
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
                    } catch (_: Exception) {
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
                cacheInfoMap[normalizedUrl] = old?.copy(
                    totalPages = old.totalPages + totalPages,
                    totalSize = old.totalSize + totalSize,
                    pagesWithImages = old.pagesWithImages + pagesWithImages,
                    title = old.title ?: novelCache.title
                )
                    ?: CacheInfo(
                        url = normalizedUrl,
                        totalPages = totalPages,
                        totalSize = totalSize,
                        pagesWithImages = pagesWithImages,
                        title = novelCache.title
                    )
            }

            _uiState.update { it.copy(cacheInfoMap = cacheInfoMap) }
        } catch (e: Exception) {
            Log.e(logTag, "从内存索引刷新缓存信息失败", e)
            _uiState.update { it.copy(cacheInfoMap = emptyMap()) }
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

    private suspend fun showShortToast(message: String) {
        withContext(Dispatchers.Main) {
            YamiboToast.show(context = applicationContext, message = message)
        }
    }

    fun checkNovelUpdate(favorite: Favorite) {
        UpdateCheckEngine.ensureInit(applicationContext)
        UpdateCheckEngine.checkNovel(favorite)
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
        UpdateCheckEngine.ensureInit(applicationContext)
        UpdateCheckEngine.checkManga(favorite, overrideStrategy, overrideSearchKeyword, overrideCleanBookName)
    }

    fun clearMangaUpdateCheckFlag(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MangaUpdateCheckUtil.clearUpdateFlagSuspend(url)
        }
    }

    fun getSearchCooldownRemainingMs(): Long {
        val elapsed = System.currentTimeMillis() - org.shirakawatyu.yamibo.novel.global.GlobalData.lastSearchTimestamp.get()
        val remaining = 15_000L - elapsed
        return remaining.coerceAtLeast(0L)
    }

    /** 当前已启用自动检查的总数（小说 + 漫画），用于配额校验。 */
    private suspend fun autoCheckEnabledCountSuspend(): Int {
        val n = NovelUpdateCheckUtil.getMapSuspend().values.count { it.autoCheckEnabled }
        val m = MangaUpdateCheckUtil.getMapSuspend().values.count { it.autoCheckEnabled }
        return n + m
    }

    fun saveNovelAutoCheck(url: String, enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                val already = NovelUpdateCheckUtil.getMapSuspend()[url]?.autoCheckEnabled == true
                if (!already && autoCheckEnabledCountSuspend() >= MAX_AUTO_CHECK) {
                    showShortToast("自动检查已达上限（$MAX_AUTO_CHECK），请先关闭其它项目")
                    return@launch
                }
            }
            NovelUpdateCheckUtil.updateAutoCheckSuspend(
                url,
                enabled,
                intervalHours
            )
            if (enabled) AutoUpdateCheckScheduler.triggerNow(applicationContext)
        }
    }

    fun saveMangaAutoCheck(url: String, enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                val already = MangaUpdateCheckUtil.getMapSuspend()[url]?.autoCheckEnabled == true
                if (!already && autoCheckEnabledCountSuspend() >= MAX_AUTO_CHECK) {
                    showShortToast("自动检查已达上限（$MAX_AUTO_CHECK），请先关闭其它项目")
                    return@launch
                }
            }
            MangaUpdateCheckUtil.updateAutoCheckSuspend(
                url,
                enabled,
                intervalHours
            )
            if (enabled) AutoUpdateCheckScheduler.triggerNow(applicationContext)
        }
    }

}
