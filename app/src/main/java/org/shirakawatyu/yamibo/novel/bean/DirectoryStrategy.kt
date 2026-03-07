package org.shirakawatyu.yamibo.novel.bean

/**
 * 漫画目录的更新/获取策略
 */
enum class DirectoryStrategy {
    /**
     * 通过Tag ID获取。更新时直接重新拉取该Tag列表。
     */
    TAG,

    /**
     * 同页超链接提取。更新时必须降级触发SEARCH。
     */
    LINKS,

    /**
     * 既没有Tag也没有同页链接，只有当前一话。更新时触发SEARCH。
     */
    PENDING_SEARCH,

    /**
     * 已经通过书名进行过版块内全局搜索。更新时继续使用SEARCH。
     */
    SEARCHED
}