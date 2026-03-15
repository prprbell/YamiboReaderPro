package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface MangaApi {
    // 获取Tag列表页
    // 设置mobile=no并伪装PC端User-Agent
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    @GET("/misc.php?mod=tag&type=thread&mobile=no")
    suspend fun getTagPageHtml(
        @Query("id") tagId: String,
        @Query("page") page: Int = 1
    ): ResponseBody

    @GET("/search.php?mod=forum&searchsubmit=yes&mobile=2")
    suspend fun searchForum(
        @Query("srchfid[]") fid: String = "30",
        @Query("srchtxt") keyword: String,
        @Query("srchtype") type: String = "title"
    ): ResponseBody

    // 用于搜索结果的后续翻页
    @GET("/search.php?mod=forum&orderby=dateline&ascdesc=desc&searchsubmit=yes&mobile=2")
    suspend fun searchForumPage(
        @Query("searchid") searchid: String,
        @Query("page") page: Int
    ): ResponseBody
}