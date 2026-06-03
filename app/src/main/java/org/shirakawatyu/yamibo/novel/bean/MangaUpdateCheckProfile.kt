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
    val lastCheckTime: Long = 0L
)
