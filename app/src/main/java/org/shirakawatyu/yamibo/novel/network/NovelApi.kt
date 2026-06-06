package org.shirakawatyu.yamibo.novel.network
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
interface NovelApi {
    // 获取帖子第一页
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @GET("api/mobile/index.php?module=viewthread&version=1")
    suspend fun getThreadFirstPage(
        @Query("tid") tid: String,
        @Query("page") page: Int = 1
    ): ResponseBody

    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @GET("api/mobile/index.php?module=viewthread&version=1&ppp=1")
    suspend fun getThreadMetaLight(
        @Query("tid") tid: String
    ): ResponseBody
    // 获取作者特定页的内容
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @GET("api/mobile/index.php?module=viewthread&version=1")
    suspend fun getThreadPageByAuthor(
        @Query("tid") tid: String,
        @Query("page") page: Int,
        @Query("authorid") authorid: String
    ): ResponseBody

    // 获取帖子元信息
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @GET("api/mobile/index.php?module=viewthread&version=1")
    suspend fun getThreadMeta(
        @Query("tid") tid: String,
        @Query("authorid") authorid: String,
        @Query("ppp") ppp: Int = 1
    ): ResponseBody
}