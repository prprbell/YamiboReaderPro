package org.shirakawatyu.yamibo.novel.bean

data class ReaderSettings(
    var fontSizePx: Float? = null,
    var lineHeightPx: Float? = null,
    var paddingDp: Float? = null,
    var nightMode: Boolean? = null,
    var backgroundColor: String? = null,
    var loadImages: Boolean? = null,
    var isVerticalMode: Boolean? = null,
    var translationMode: Int? = null,
    var fontFamily: Int? = null
)
