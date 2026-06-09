package org.shirakawatyu.yamibo.novel.bean

/** 漫画手动更新检查策略。 */
enum class MangaUpdateCheckStrategy { TAG, SEARCH }

/**
 * 漫画手动更新检查档案。
 *
 * 不再表示“特别关注”，只保存手动检查所需的目录关联、策略、关键词和章节快照。
 */
data class MangaUpdateCheckProfile(
    val title: String,
    val url: String,
    val cleanBookName: String,
    val searchKeyword: String?,
    val strategy: MangaUpdateCheckStrategy,
    val savedChapterCount: Int,
    val savedLatestTid: String,
    val hasUpdate: Boolean = false,
    val lastCheckTime: Long = 0L,
    /** 上次进入自动检查队列并开始尝试的时间；用于队列公平排序，不等同于成功检查时间。 */
    val lastAutoCheckAttemptTime: Long = 0L,
    val autoCheckEnabled: Boolean = false,
    val autoCheckIntervalHours: Int = 6
)
