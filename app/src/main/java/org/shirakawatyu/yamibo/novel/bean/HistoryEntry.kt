package org.shirakawatyu.yamibo.novel.bean

data class HistoryEntry(
    val url: String,
    val title: String,
    val author: String,
    val section: String,
    val timestamp: Long
)
