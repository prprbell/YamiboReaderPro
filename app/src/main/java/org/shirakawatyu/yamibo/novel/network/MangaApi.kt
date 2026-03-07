package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface MangaApi {
    // 获取 Tag 列表页 (强制走 PC 端获取全部列表)
    @GET("/misc.php?mod=tag&type=thread")
    suspend fun getTagPageHtml(@Query("id") tagId: String): ResponseBody

    // 发起版块内搜索 (30: 中文百合漫画区)
    @GET("/search.php?mod=curforum&mobile=2")
    suspend fun searchForum(
        @Query("srhfid") fid: String = "30",
        @Query("srchtxt") keyword: String
    ): ResponseBody
}