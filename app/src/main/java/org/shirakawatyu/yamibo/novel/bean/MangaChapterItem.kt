package org.shirakawatyu.yamibo.novel.bean

import com.alibaba.fastjson2.annotation.JSONCreator
import com.alibaba.fastjson2.annotation.JSONField

/**
 * 单个漫画章节的数据模型
 */
data class MangaChapterItem @JSONCreator constructor(
    @JSONField(name = "tid")
    val tid: String,                  // 帖子ID，绝对主键，用于去重合并

    @JSONField(name = "rawTitle")
    val rawTitle: String,             // 原始帖子标题

    @JSONField(name = "chapterNum")
    val chapterNum: Float,            // 正则提取出的话数，用于强制排序

    @JSONField(name = "url")
    val url: String,                  // 跳转链接

    @JSONField(name = "authorUid")
    val authorUid: String?,           // 发帖人UID

    @JSONField(name = "authorName")
    val authorName: String?           // 发帖人昵称，用于UI展示
)