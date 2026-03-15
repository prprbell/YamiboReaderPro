package org.shirakawatyu.yamibo.novel.bean

import com.alibaba.fastjson2.annotation.JSONCreator
import com.alibaba.fastjson2.annotation.JSONField

/**
 * 整部漫画的目录档案
 */
data class MangaDirectory @JSONCreator constructor(
    @JSONField(name = "cleanBookName")
    val cleanBookName: String,               // 纯净书名

    @JSONField(name = "strategy")
    val strategy: DirectoryStrategy,         // 更新策略

    @JSONField(name = "sourceKey")
    val sourceKey: String,                   // 策略的依据：如果是TAG，存TagId；如果是SEARCH，存书名

    @JSONField(name = "chapters")
    val chapters: List<MangaChapterItem> = emptyList(), // 章节列表

    @JSONField(name = "isOneShot")
    val isOneShot: Boolean = false,           // 是否被判定为短篇/单本

    @JSONField(name = "lastUpdateTime")
    var lastUpdateTime: Long = 0L,

    @JSONField(name = "searchKeyword")
    val searchKeyword: String? = null
)