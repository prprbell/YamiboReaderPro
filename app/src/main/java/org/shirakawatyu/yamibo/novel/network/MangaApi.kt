package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface MangaApi {
    // 获取 Tag 列表页 (强制走 PC 端获取全部列表)
    // 【修复】添加 mobile=no 并伪装 PC 端 User-Agent，彻底阻止论坛重定向到手机版
    // 获取 Tag 列表页 (强制走 PC 端获取全部列表)
    // 加入 mobile=no 并伪装 PC 端 User-Agent，彻底阻止论坛重定向到手机版
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    @GET("/misc.php?mod=tag&type=thread&mobile=no")
    suspend fun getTagPageHtml(
        @Query("id") tagId: String,
        @Query("page") page: Int = 1
    ): ResponseBody

    @GET("/search.php?mod=forum&searchsubmit=yes&mobile=2")
    suspend fun searchForum(
        // 3. 限制版块的参数在PHP后端叫 srchfid[]
        @Query("srchfid[]") fid: String = "30",
        @Query("srchtxt") keyword: String,
        // 4. 建议只搜索标题，过滤掉因为正文提到该漫画而造成的干扰项
        @Query("srchtype") type: String = "title"
    ): ResponseBody
}