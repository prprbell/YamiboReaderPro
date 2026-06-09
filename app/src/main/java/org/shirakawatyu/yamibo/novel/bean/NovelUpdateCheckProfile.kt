package org.shirakawatyu.yamibo.novel.bean

/**
 * 小说手动更新检查档案。
 *
 * 不再表示“特别关注”，只保存某个收藏项的更新检查基线：
 * - savedReplies：上次检查后保存的回复数
 * - hasUpdate：本地是否有待读更新提示
 * - lastCheckTime：上次实际发起检查的时间，用于 1 小时间隔防抖
 */
data class NovelUpdateCheckProfile(
    val title: String,
    val url: String,
    val authorId: String,
    val savedReplies: Int,
    val hasUpdate: Boolean = false,
    val lastCheckTime: Long = 0L,
    /** 上次进入自动检查队列并开始尝试的时间；用于队列公平排序，不等同于成功检查时间。 */
    val lastAutoCheckAttemptTime: Long = 0L,
    val autoCheckEnabled: Boolean = false,
    val autoCheckIntervalHours: Int = 6
)
