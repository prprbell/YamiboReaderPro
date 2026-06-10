package org.shirakawatyu.yamibo.novel.util.updateCheck

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckStrategy
import org.shirakawatyu.yamibo.novel.bean.NovelUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.OtherUpdateCheckProfile
import kotlin.random.Random

/**
 * 应用级（进程生命周期）自动更新检查调度器。
 *
 * 职责：决定"谁该查 / 何时查 / 怎么错峰 / 串行间隔"，真正的检查委托给 [UpdateCheckEngine]。
 * 触发时机：App / 收藏页进入前台时（onAppForeground），或启用自动检查后（triggerNow）。
 * 后台语义：跑在自己的进程级作用域上，切换界面不打断；结果写 DataStore，UI 自动刷新角标。
 */
object AutoUpdateCheckScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()

    @Volatile private var lastRunAt = 0L
    @Volatile private var pendingRunAfterCurrent = false

    // 前台触发的最小间隔：避免来回切界面频繁扫描
    private const val MIN_RUN_GAP_MS = 60_000L

    // 普通项串行间隔 + 抖动
    private const val INTER_CHECK_GAP_MS = 1_500L
    private const val INTER_CHECK_JITTER_MS = 1_200L

    // 漫画"搜索"策略受论坛 10s 搜索冷却约束，保留少量余量再错峰。
    private const val MANGA_SEARCH_GAP_MS = 12_500L

    private sealed interface Due {
        val url: String
        data class Novel(val p: NovelUpdateCheckProfile) : Due { override val url get() = p.url }
        data class Manga(val p: MangaUpdateCheckProfile) : Due { override val url get() = p.url }
        data class Other(val p: OtherUpdateCheckProfile) : Due { override val url get() = p.url }
    }

    private data class DueCandidate(
        val item: Due,
        val nextDueAt: Long,
        val overdueMs: Long,
        val phase: Long,
        val mangaPriority: Int
    )

    /** App / 收藏页进入前台时调用（带最小间隔节流）。 */
    fun onAppForeground(appContext: Context) {
        UpdateCheckEngine.ensureInit(appContext)
        requestRun(force = false)
    }

    /** 启用自动检查后立刻尝试跑一轮（忽略最小间隔）。 */
    fun triggerNow(appContext: Context) {
        UpdateCheckEngine.ensureInit(appContext)
        requestRun(force = true)
    }

    private fun requestRun(force: Boolean) {
        if (force) pendingRunAfterCurrent = true
        scope.launch { runDueChecks(force = force) }
    }

    private suspend fun runDueChecks(force: Boolean) {
        if (!isLoggedIn()) return
        // 同一时刻只允许一轮在跑；强制触发如果撞上正在跑的队列，只登记“跑完后继续扫”。
        if (!runMutex.tryLock()) {
            if (force) pendingRunAfterCurrent = true
            return
        }

        try {
            var bypassForegroundGap = force

            while (isLoggedIn()) {
                val now = System.currentTimeMillis()
                if (!bypassForegroundGap && now - lastRunAt < MIN_RUN_GAP_MS) return
                lastRunAt = now
                pendingRunAfterCurrent = false

                val item = selectNextDue(now) ?: return

                when (item) {
                    is Due.Novel -> UpdateCheckEngine.runAutoNovel(item.p)
                    is Due.Manga -> UpdateCheckEngine.runAutoManga(item.p)
                    is Due.Other -> UpdateCheckEngine.runAutoOther(item.p)
                }

                val hasMoreDue = selectNextDue(System.currentTimeMillis()) != null
                if (!pendingRunAfterCurrent && !hasMoreDue) return

                delay(gapAfter(item))
                // 同一条后台队列内部继续 drain，不再受前台 60s 节流影响。
                bypassForegroundGap = true
            }
        } finally {
            runMutex.unlock()
        }
    }

    /**
     * 动态选择“下一本该查的书”，而不是一次性截取固定前 16 个。
     *
     * 优先级：
     * 1. 从未检查/最逾期的条目；
     * 2. 同等逾期时漫画优先，因为漫画检查还负责刷新本地目录；
     * 3. 最后用固定相位打散顺序，避免每次完全按保存顺序请求。
     */
    private suspend fun selectNextDue(now: Long): Due? {
        val novels = NovelUpdateCheckUtil.getMapSuspend().values
            .asSequence()
            .filter {
                it.autoCheckEnabled &&
                        !it.hasUpdate &&
                        !it.authorId.isNullOrBlank() &&
                        !UpdateCheckEngine.isChecking(it.url)
            }
            .map { Due.Novel(it) }

        val mangas = MangaUpdateCheckUtil.getMapSuspend().values
            .asSequence()
            .filter {
                // 漫画即使已有“新”胶囊也继续到期检查：
                // 检查过程会同步刷新本地漫画目录，否则连续更新时目录会停在第一次更新。
                it.autoCheckEnabled &&
                        !UpdateCheckEngine.isChecking(it.url)
            }
            .map { Due.Manga(it) }

        val others = OtherUpdateCheckUtil.getMapSuspend().values
            .asSequence()
            .filter {
                it.autoCheckEnabled &&
                        !it.hasUpdate &&
                        !UpdateCheckEngine.isChecking(it.url)
            }
            .map { Due.Other(it) }

        return (novels + mangas + others)
            .mapNotNull { candidateOf(it, now) }
            .sortedWith(
                compareByDescending<DueCandidate> { it.overdueMs }
                    .thenByDescending { it.mangaPriority }
                    .thenBy { it.phase }
            )
            .firstOrNull()
            ?.item
    }

    private fun candidateOf(item: Due, now: Long): DueCandidate? {
        val lastCheck = when (item) {
            is Due.Novel -> item.p.lastCheckTime
            is Due.Manga -> item.p.lastCheckTime
            is Due.Other -> item.p.lastCheckTime
        }
        val intervalHours = when (item) {
            is Due.Novel -> item.p.autoCheckIntervalHours
            is Due.Manga -> item.p.autoCheckIntervalHours
            is Due.Other -> item.p.autoCheckIntervalHours
        }
        val intervalMs = intervalHours.coerceAtLeast(1) * 3_600_000L
        val nextDueAt = nextDueAt(item.url, lastCheck, intervalMs)
        if (nextDueAt > now) return null

        return DueCandidate(
            item = item,
            nextDueAt = nextDueAt,
            overdueMs = if (lastCheck <= 0L) Long.MAX_VALUE else now - nextDueAt,
            phase = phaseOf(item.url, 3_600_000L),
            mangaPriority = if (item is Due.Manga) 1 else 0
        )
    }

    private fun gapAfter(item: Due): Long {
        val isMangaSearch = item is Due.Manga &&
                item.p.strategy == MangaUpdateCheckStrategy.SEARCH
        return if (isMangaSearch) {
            MANGA_SEARCH_GAP_MS + Random.nextLong(INTER_CHECK_JITTER_MS)
        } else {
            INTER_CHECK_GAP_MS + Random.nextLong(INTER_CHECK_JITTER_MS)
        }
    }

    /** 固定相位（按 url 哈希），落在 [0, windowMs)。 */
    private fun phaseOf(url: String, windowMs: Long): Long {
        val h = url.hashCode().toLong() and 0x7fffffffffffffffL
        return if (windowMs <= 0L) 0L else h % windowMs
    }

    /** 计算下一次应检查的时间。lastCheck <= 0 表示从未检查，应立即进入队列。 */
    private fun nextDueAt(url: String, lastCheck: Long, intervalMs: Long): Long {
        if (lastCheck <= 0L) return 0L
        val phase = phaseOf(url, intervalMs)
        val bucketLast = (lastCheck + phase) / intervalMs
        return ((bucketLast + 1) * intervalMs) - phase
    }

    private fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://bbs.yamibo.com")
            ?.contains("EeqY_2132_auth=") == true
}
