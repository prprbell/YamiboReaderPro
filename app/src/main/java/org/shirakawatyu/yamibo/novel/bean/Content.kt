package org.shirakawatyu.yamibo.novel.bean

/**
 * 内容数据类，用于表示小说章节中的内容
 *
 * @param data 内容数据，可以是文本内容或图片
 * @param type 内容类型，标识是图片还是文本
 * @param chapterTitle 章节标题，可为空
 */
data class Content(
    val data: String,
    val type: ContentType,
    val chapterTitle: String? = null
)

enum class ContentType {
    IMG, TEXT
}


