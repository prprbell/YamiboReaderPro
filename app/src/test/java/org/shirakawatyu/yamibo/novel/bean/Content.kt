package org.shirakawatyu.yamibo.novel.bean

data class Content(
    val data: String,
    val type: ContentType
)

enum class ContentType {
    IMG, TEXT
}


