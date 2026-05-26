package org.shirakawatyu.yamibo.novel.bean

import com.alibaba.fastjson2.annotation.JSONCreator
import com.alibaba.fastjson2.annotation.JSONField

data class ReaderSettings @JSONCreator constructor(
    @JSONField(name = "fontSizePx")
    var fontSizePx: Float? = null,

    @JSONField(name = "lineHeightPx")
    var lineHeightPx: Float? = null,

    @JSONField(name = "paddingDp")
    var paddingDp: Float? = null,

    @JSONField(name = "nightMode")
    var nightMode: Boolean? = null,

    @JSONField(name = "backgroundColor")
    var backgroundColor: String? = null,

    @JSONField(name = "loadImages")
    var loadImages: Boolean? = null,

    @JSONField(name = "isVerticalMode", alternateNames = ["verticalMode"])
    var isVerticalMode: Boolean? = null,

    @JSONField(name = "translationMode")
    var translationMode: Int? = null,

    @JSONField(name = "fontFamily")
    var fontFamily: Int? = null
)
