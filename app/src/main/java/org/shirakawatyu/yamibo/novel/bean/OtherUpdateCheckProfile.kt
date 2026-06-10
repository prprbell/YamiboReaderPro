package org.shirakawatyu.yamibo.novel.bean

data class OtherUpdateCheckProfile(
    val title: String,
    val url: String,
    val savedReplies: Int,
    val hasUpdate: Boolean = false,
    val lastCheckTime: Long = 0L,
    val autoCheckEnabled: Boolean = false,
    val autoCheckIntervalHours: Int = 12
)
