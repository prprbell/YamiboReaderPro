package org.shirakawatyu.yamibo.novel.util.updateCheck

import android.content.Context
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckStrategy
import org.shirakawatyu.yamibo.novel.bean.NovelUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.OtherUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.network.NovelApi
import org.shirakawatyu.yamibo.novel.ui.widget.YamiboToast
import org.shirakawatyu.yamibo.novel.repository.DirectoryRepository

/**
 * 应用级（进程生命周期）更新检查引擎。
 *
 * - 手动检查（滑动卡片）与后台自动检查共用同一套执行逻辑与节流锁，避免重复实现与互相打架。
 * - 通过 [inFlight] 暴露"正在检查"的 url 集合；UI 据此显示转圈，对手动/后台都生效。
 * - 所有结果都写入 DataStore；UI 通过各自的 Flow 自动刷新角标。
 * - notify=true 时弹 Toast（手动场景）；notify=false 时全程静默（后台场景）。
 */
object UpdateCheckEngine {

    // 进程级作用域：不随任何 ViewModel / Composition 销毁而取消 —— 这是"后台不被打断"的根基。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun ensureInit(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("UpdateCheckEngine 未初始化，请先调用 ensureInit(context)")

    // ---- 正在检查的 url 集合（手动 + 后台共用）----
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    fun isChecking(url: String): Boolean = _inFlight.value.contains(url)

    private val inFlightMutex = Mutex()

    private suspend fun tryEnter(url: String): Boolean =
        inFlightMutex.withLock {
            if (url in _inFlight.value) false
            else {
                _inFlight.value = _inFlight.value + url
                true
            }
        }

    private suspend fun leave(url: String) =
        inFlightMutex.withLock {
            _inFlight.value = _inFlight.value - url
        }

    // ---- 节流：按 url hash 的条带锁，同 url 必互斥，不同书大概率并行 ----
    private const val CHECK_LOCK_STRIPES = 16
    private val urlCheckLocks = Array(CHECK_LOCK_STRIPES) { Mutex() }
    private fun checkLockFor(url: String): Mutex =
        urlCheckLocks[(url.hashCode() and Int.MAX_VALUE) % CHECK_LOCK_STRIPES]

    // ---- 小说元数据请求节流（原 VM 里的逻辑搬来）----
    private val novelMetaRequestMutex = Mutex()
    private const val NOVEL_META_REQUEST_INTERVAL_MS = 400L
    @Volatile private var lastNovelMetaRequestStartedAt = 0L

    private fun extractTid(url: String): String? =
        Regex("tid=(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun toast(message: String) {
        val ctx = appContext ?: return
        withContext(Dispatchers.Main) {
            YamiboToast.show(context = ctx, message = message)
        }
    }

    // =========================================================================
    // 对外入口
    // =========================================================================

    /** 手动检查小说（来自滑动卡片）。 */
    fun checkNovel(favorite: Favorite) {
        if (favorite.type != 1) return
        scope.launch { performNovel(favorite.url, favorite.title, favorite.authorId, notify = true) }
    }

    /** 手动检查"其他"帖子（来自滑动卡片）。不需要 authorId。 */
    fun checkOther(favorite: Favorite) {
        if (favorite.type != 3) return
        scope.launch { performOther(favorite.url, favorite.title, notify = true) }
    }

    /** 手动检查漫画（来自滑动卡片 / 配置弹窗）。 */
    fun checkManga(
        favorite: Favorite,
        overrideStrategy: MangaUpdateCheckStrategy? = null,
        overrideSearchKeyword: String? = null,
        overrideCleanBookName: String? = null
    ) {
        if (favorite.type != 2) return
        scope.launch {
            performManga(
                favorite.url, favorite.title,
                overrideStrategy, overrideSearchKeyword, overrideCleanBookName,
                notify = true
            )
        }
    }

    /**
     * 手动检查漫画（挂起版本，调用方可按需串行后续操作）。
     * tryEnter 保证原子去重；同一 url 已在检查时直接返回，不弹 Toast。
     */
    suspend fun checkMangaSuspend(
        favorite: Favorite,
        overrideStrategy: MangaUpdateCheckStrategy? = null,
        overrideSearchKeyword: String? = null,
        overrideCleanBookName: String? = null
    ) {
        if (favorite.type != 2) return
        performManga(favorite.url, favorite.title, overrideStrategy, overrideSearchKeyword, overrideCleanBookName, notify = true)
    }

    /** 类型探测后静默建立"追踪更新"基线：不弹 Toast，不改变自动检查开关。 */
    fun trackNovelSilently(url: String, title: String, authorId: String?) {
        scope.launch { performNovel(url, title, authorId, notify = false) }
    }

    fun trackOtherSilently(url: String, title: String) {
        scope.launch { performOther(url, title, notify = false) }
    }

    /** 类型探测后静默建立"追踪更新"基线：不弹 Toast，不改变自动检查开关。 */
    fun trackMangaSilently(url: String, title: String) {
        scope.launch { performManga(url, title, null, null, null, notify = false) }
    }

    /** 后台自动检查（由调度器串行调用，已在引擎作用域内，需挂起等待完成以便错峰）。 */
    suspend fun runAutoNovel(profile: NovelUpdateCheckProfile) {
        performNovel(profile.url, profile.title, profile.authorId, notify = false)
    }

    suspend fun runAutoManga(profile: MangaUpdateCheckProfile) {
        // 自动检查复用 profile 里已保存的策略/关键词：全部传 null，让内部回退到 oldProfile。
        performManga(profile.url, profile.title, null, null, null, notify = false)
    }

    suspend fun runAutoOther(profile: OtherUpdateCheckProfile) {
        performOther(profile.url, profile.title, notify = false)
    }

    // =========================================================================
    // 实际执行（设置/清除 inFlight + 真正的网络逻辑）
    // =========================================================================

    private suspend fun performNovel(url: String, title: String, authorId: String?, notify: Boolean) {
        if (!tryEnter(url)) {
            if (notify) toast("正在查询更新")
            return
        }
        try {
            runNovelUpdateCheck(url, title, authorId, notify)
        } finally {
            leave(url)
        }
    }

    private suspend fun performManga(
        url: String, title: String,
        overrideStrategy: MangaUpdateCheckStrategy?,
        overrideSearchKeyword: String?,
        overrideCleanBookName: String?,
        notify: Boolean
    ) {
        if (!tryEnter(url)) {
            if (notify) toast("正在查询更新")
            return
        }
        try {
            runMangaUpdateCheck(url, title, overrideStrategy, overrideSearchKeyword, overrideCleanBookName, notify)
        } finally {
            leave(url)
        }
    }

    private suspend fun performOther(url: String, title: String, notify: Boolean) {
        if (!tryEnter(url)) {
            if (notify) toast("正在查询更新")
            return
        }
        try {
            runOtherUpdateCheck(url, title, notify)
        } finally {
            leave(url)
        }
    }

    private suspend fun fetchOtherRepliesQueued(tid: String): Int? {
        return novelMetaRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastNovelMetaRequestStartedAt
            if (elapsed in 0L until NOVEL_META_REQUEST_INTERVAL_MS) {
                delay(NOVEL_META_REQUEST_INTERVAL_MS - elapsed)
            }
            lastNovelMetaRequestStartedAt = System.currentTimeMillis()

            val novelApi = YamiboRetrofit.getInstance().create(NovelApi::class.java)
            val resp = novelApi.getThreadMetaLight(tid).string()
            val json = JSON.parseObject(resp)
            val thread = json.getJSONObject("Variables")?.getJSONObject("thread")
            thread?.getString("replies")?.toIntOrNull()
        }
    }

    private suspend fun fetchNovelRepliesQueued(tid: String, authorId: String): Int? {
        return novelMetaRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastNovelMetaRequestStartedAt
            if (elapsed in 0L until NOVEL_META_REQUEST_INTERVAL_MS) {
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

    // ---- 小说检查（搬自原 FavoriteVM.runNovelUpdateCheck，新增 notify 门控）----
    private suspend fun runNovelUpdateCheck(url: String, title: String, authorId: String?, notify: Boolean) {
        val tid = extractTid(url)
        if (tid == null) { if (notify) toast("查询失败，无法识别帖子ID"); return }
        if (authorId.isNullOrBlank()) { if (notify) toast("查询失败，缺少作者ID"); return }

        checkLockFor(url).withLock {
            val profile = NovelUpdateCheckUtil.getMapSuspend()[url]
            try {
                val currentReplies = fetchNovelRepliesQueued(tid, authorId)
                if (currentReplies == null) {
                    if (notify) toast("查询失败：没有读取到回复数")
                    profile?.let { NovelUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis()) }
                    return@withLock
                }
                val checkedAt = System.currentTimeMillis()
                if (profile == null) {
                    // 首次追踪：建立基线，不算"有更新"
                    NovelUpdateCheckUtil.saveProfileSuspend(
                        NovelUpdateCheckProfile(
                            title = title, url = url, authorId = authorId,
                            savedReplies = currentReplies, hasUpdate = false, lastCheckTime = checkedAt
                        )
                    )
                    if (notify) toast("已开始追踪更新")
                    return@withLock
                }
                val detectedUpdate = currentReplies > profile.savedReplies
                val keepUnreadUpdate = profile.hasUpdate || detectedUpdate
                NovelUpdateCheckUtil.updateRepliesSuspend(
                    url = url, newReplies = currentReplies,
                    hasUpdate = keepUnreadUpdate, lastCheckTime = checkedAt
                )
                if (notify) toast(if (detectedUpdate) "检测到小说更新" else "没有检测到小说更新")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 失败也要更新 lastCheckTime，避免错峰窗口反复立刻重试
                profile?.let { NovelUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis()) }
                if (notify) toast("查询小说更新失败")
            }
        }
    }

    // ---- "其他"帖子检查（不需要 authorId，使用 getThreadMetaLight）----
    private suspend fun runOtherUpdateCheck(url: String, title: String, notify: Boolean) {
        val tid = extractTid(url)
        if (tid == null) { if (notify) toast("查询失败，无法识别帖子ID"); return }

        checkLockFor(url).withLock {
            val profile = OtherUpdateCheckUtil.getMapSuspend()[url]
            try {
                val currentReplies = fetchOtherRepliesQueued(tid)
                if (currentReplies == null) {
                    if (notify) toast("查询失败：没有读取到回复数")
                    profile?.let { OtherUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis()) }
                    return@withLock
                }
                val checkedAt = System.currentTimeMillis()
                if (profile == null) {
                    OtherUpdateCheckUtil.saveProfileSuspend(
                        OtherUpdateCheckProfile(
                            title = title, url = url,
                            savedReplies = currentReplies, hasUpdate = false, lastCheckTime = checkedAt
                        )
                    )
                    if (notify) toast("已开始追踪更新")
                    return@withLock
                }
                val detectedUpdate = currentReplies > profile.savedReplies
                val keepUnreadUpdate = profile.hasUpdate || detectedUpdate
                OtherUpdateCheckUtil.updateRepliesSuspend(
                    url = url, newReplies = currentReplies,
                    hasUpdate = keepUnreadUpdate, lastCheckTime = checkedAt
                )
                if (notify) toast(if (detectedUpdate) "检测到更新" else "没有检测到更新")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                profile?.let { OtherUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis()) }
                if (notify) toast("查询更新失败")
            }
        }
    }

    // ---- 漫画检查（搬自原 FavoriteVM.runMangaUpdateCheck，新增 notify 门控）----
    private suspend fun runMangaUpdateCheck(
        url: String, title: String,
        overrideStrategy: MangaUpdateCheckStrategy?,
        overrideSearchKeyword: String?,
        overrideCleanBookName: String?,
        notify: Boolean
    ) {
        val ctx = requireContext()
        val tid = extractTid(url)
        if (tid == null) { if (notify) toast("查询失败，无法识别帖子ID"); return }

        checkLockFor(url).withLock {
            val repo = DirectoryRepository.getInstance(ctx)
            val oldProfile = MangaUpdateCheckUtil.getMapSuspend()[url]
            try {
                var mangaDir = if (oldProfile != null) {
                    repo.getDirectoryByCleanName(oldProfile.cleanBookName)
                        ?: repo.getAllDirectories().find { dir -> dir.chapters.any { it.tid == tid } }
                } else {
                    repo.getAllDirectories().find { dir -> dir.chapters.any { it.tid == tid } }
                }

                if (mangaDir == null) {
                    val mangaApi = YamiboRetrofit.getInstance().create(MangaApi::class.java)
                    val resp = mangaApi.getThreadDetailApi(tid).string()
                    val json = JSON.parseObject(resp)
                    val postlist = json.getJSONObject("Variables")?.getJSONArray("postlist")
                    val message = postlist?.getJSONObject(0)?.getString("message")
                    if (message.isNullOrBlank()) {
                        if (notify) toast("初始化漫画目录失败")
                        return@withLock
                    }
                    val html = "<div class=\"message\">$message</div>"
                    mangaDir = repo.initDirectoryForThread(tid, url, title, html)
                }

                if (overrideCleanBookName != null && overrideCleanBookName.isNotBlank() &&
                    overrideCleanBookName != mangaDir.cleanBookName
                ) {
                    val newKeyword = overrideSearchKeyword ?: mangaDir.searchKeyword ?: ""
                    mangaDir = repo.renameAndMergeDirectory(mangaDir, overrideCleanBookName, newKeyword)
                }

                val strategy = overrideStrategy
                    ?: oldProfile?.strategy
                    ?: if (mangaDir.strategy == DirectoryStrategy.TAG)
                        MangaUpdateCheckStrategy.TAG else MangaUpdateCheckStrategy.SEARCH

                val keyword = overrideSearchKeyword ?: oldProfile?.searchKeyword ?: mangaDir.searchKeyword
                val cleanBookName = overrideCleanBookName ?: oldProfile?.cleanBookName ?: mangaDir.cleanBookName
                val baseChapterCount = oldProfile?.savedChapterCount ?: mangaDir.chapters.size
                val baseLatestTid = oldProfile?.savedLatestTid ?: (mangaDir.chapters.lastOrNull()?.tid ?: "")

                val isFirstCheck = oldProfile == null
                if (isFirstCheck) {
                    MangaUpdateCheckUtil.saveProfileSuspend(
                        MangaUpdateCheckProfile(
                            title = title, url = url, cleanBookName = cleanBookName,
                            searchKeyword = keyword, strategy = strategy,
                            savedChapterCount = baseChapterCount, savedLatestTid = baseLatestTid,
                            hasUpdate = false, lastCheckTime = 0L
                        )
                    )
                } else {
                    val existing = oldProfile!!
                    if (existing.searchKeyword != keyword || existing.strategy != strategy) {
                        MangaUpdateCheckUtil.saveProfileSuspend(
                            existing.copy(title = title, searchKeyword = keyword, strategy = strategy)
                        )
                    }
                }

                val dirForUpdate = if (keyword != mangaDir.searchKeyword) {
                    mangaDir.copy(searchKeyword = keyword)
                } else mangaDir

                val forceSearch = strategy == MangaUpdateCheckStrategy.SEARCH
                val result = repo.manuallyUpdateDirectory(dirForUpdate, forceSearch = forceSearch, currentTid = tid)
                if (result.isFailure) {
                    MangaUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis())
                    if (notify) toast("查询漫画更新失败")
                    return@withLock
                }

                val updatedDir = result.getOrThrow().directory
                val newCount = updatedDir.chapters.size
                val newLatestTid = updatedDir.chapters.lastOrNull()?.tid ?: ""
                val snapshotCount = if (newCount > 0) newCount else baseChapterCount
                val snapshotLatestTid = newLatestTid.ifEmpty { baseLatestTid }
                val detectedUpdate = newCount > baseChapterCount ||
                        (newLatestTid.isNotEmpty() && baseLatestTid.isNotEmpty() && newLatestTid != baseLatestTid)

                val keepUnreadUpdate = oldProfile?.hasUpdate == true || (!isFirstCheck && detectedUpdate)
                MangaUpdateCheckUtil.updateSnapshotSuspend(
                    url = url,
                    chapterCount = snapshotCount,
                    latestTid = snapshotLatestTid,
                    hasUpdate = keepUnreadUpdate,
                    lastCheckTime = System.currentTimeMillis(),
                    searchKeyword = keyword,
                    strategy = strategy,
                    cleanBookName = mangaDir.cleanBookName
                )
                if (notify) toast(
                    when {
                        isFirstCheck -> "已开始追踪更新"
                        detectedUpdate -> "检测到漫画更新"
                        else -> "没有检测到漫画更新"
                    }
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                MangaUpdateCheckUtil.updateCheckTimeSuspend(url, System.currentTimeMillis())
                if (notify) toast("查询漫画更新失败")
            }
        }
    }
}
