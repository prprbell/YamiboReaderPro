package org.shirakawatyu.yamibo.novel.item

data class MangaPageItem(
    val uniqueId: String,       // 唯一标识 (TID_localIndex)，用于Compose列表锚点复用
    val globalIndex: Int,       // 在大列表中的绝对索引
    val tid: String,            // 归属章节的TID
    val chapterUrl: String,     // 归属章节的URL
    val chapterTitle: String,   // 章节名
    val imageUrl: String,       // 图片URL
    val localIndex: Int,        // 在该章节内的相对页码
    val chapterTotalPages: Int  // 该章节总页数
)

data class LoadedChapter(
    val tid: String,
    val url: String,
    val title: String,
    val pages: List<MangaPageItem>
)