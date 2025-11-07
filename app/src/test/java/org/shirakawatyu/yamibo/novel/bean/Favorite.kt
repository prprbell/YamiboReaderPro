package org.shirakawatyu.yamibo.novel.bean

data class Favorite(
    var title: String,
    var url: String,
    var lastPage: Int = 0,
    var lastView: Int = 1
)
