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

    // 一轮最多处理的项目数（与配额 MAX_AUTO_CHECK 对齐）
    private const val MAX_PER_RUN = 16

    // 前台触发的最小间隔：避免来回切界面频繁扫描
    private const val MIN_RUN_GAP_MS = 60_000L

    // 普通项串行间隔 + 抖动
    private const val INTER_CHECK_GAP_MS = 1_500L
    private const val INTER_CHECK_JITTER_MS = 1_200L

    // 漫画"搜索"策略受 20s 搜索冷却约束，跟随其后需更长间隔
    private const val MANGA_SEARCH_GAP_MS = 21_000L

    private sealed interface Due {
        val url: String
        data class Novel(val p: NovelUpdateCheckProfile) : Due { override val url get() = p.url }
        data class Manga(val p: MangaUpdateCheckProfile) : Due { override val url get() = p.url }
    }

    /** App / 收藏页进入前台时调用（带最小间隔节流）。 */
    fun onAppForeground(appContext: Context) {
        UpdateCheckEngine.ensureInit(appContext)
        scope.launch { runDueChecks(force = false) }
    }

    /** 启用自动检查后立刻尝试跑一轮（忽略最小间隔）。 */
    fun triggerNow(appContext: Context) {
        UpdateCheckEngine.ensureInit(appContext)
        scope.launch { runDueChecks(force = true) }
    }

    private suspend fun runDueChecks(force: Boolean) {
        if (!isLoggedIn()) return
        // 同一时刻只允许一轮在跑；正在跑就直接跳过，避免叠加
        if (!runMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (!force && now - lastRunAt < MIN_RUN_GAP_MS) return
            lastRunAt = now

            val novels = NovelUpdateCheckUtil.getMapSuspend().values
                .filter { it.autoCheckEnabled && !it.authorId.isNullOrBlank() && isDue(it.url, it.lastCheckTime, it.autoCheckIntervalHours, now) }
                .map { Due.Novel(it) }

            val mangas = MangaUpdateCheckUtil.getMapSuspend().values
                .filter { it.autoCheckEnabled && isDue(it.url, it.lastCheckTime, it.autoCheckIntervalHours, now) }
                .map { Due.Manga(it) }

            // 错峰：按相位排序得到稳定且分散的执行顺序，并限制单轮数量
            val due = (novels + mangas)
                .sortedBy { phaseOf(it.url, 3_600_000L) }
                .take(MAX_PER_RUN)

            for ((i, item) in due.withIndex()) {
                if (!isLoggedIn()) break
                when (item) {
                    is Due.Novel -> UpdateCheckEngine.runAutoNovel(item.p)
                    is Due.Manga -> UpdateCheckEngine.runAutoManga(item.p)
                }
                if (i < due.lastIndex) {
                    val isMangaSearch = item is Due.Manga &&
                            item.p.strategy == MangaUpdateCheckStrategy.SEARCH
                    val gap = if (isMangaSearch) {
                        MANGA_SEARCH_GAP_MS + Random.nextLong(INTER_CHECK_JITTER_MS)
                    } else {
                        INTER_CHECK_GAP_MS + Random.nextLong(INTER_CHECK_JITTER_MS)
                    }
                    delay(gap)
                }
            }
        } finally {
            runMutex.unlock()
        }
    }

    /** 固定相位（按 url 哈希），落在 [0, windowMs)。 */
    private fun phaseOf(url: String, windowMs: Long): Long {
        val h = url.hashCode().toLong() and 0x7fffffffffffffffL
        return if (windowMs <= 0L) 0L else h % windowMs
    }

    /**
     * 基于"相位错位的桶"判断是否到点：
     * 周期严格为 interval，但每个项目的桶边界被自身相位平移，从而互相错开。
     */
    private fun isDue(url: String, lastCheck: Long, intervalHours: Int, now: Long): Boolean {
        val interval = intervalHours.coerceAtLeast(1) * 3_600_000L
        if (lastCheck <= 0L) return true // 从未检查 → 视为到点（串行节流会摊开首轮）
        val phase = phaseOf(url, interval)
        val bucketNow = (now + phase) / interval
        val bucketLast = (lastCheck + phase) / interval
        return bucketNow > bucketLast
    }

    private fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://bbs.yamibo.com")
            ?.contains("EeqY_2132_auth=") == true
}
