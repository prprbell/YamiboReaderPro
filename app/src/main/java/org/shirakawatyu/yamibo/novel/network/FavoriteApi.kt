package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

interface FavoriteApi {
    @GET("/home.php?mod=space&do=favorite&view=me&type=thread&mobile=2")
    fun getFavoritePage(@Query("page") page: Int): Call<ResponseBody>

    @GET("/misc.php?mod=faq")
    fun getFormHash(): Call<ResponseBody>

    // 补充 Origin 和 Referer，防止 Discuz! 拦截 POST 请求
    @Headers(
        "Origin: https://bbs.yamibo.com",
        "Referer: https://bbs.yamibo.com/home.php?mod=space&do=favorite&view=me"
    )
    @FormUrlEncoded
    @POST("/home.php?mod=spacecp&ac=favorite&op=delete&type=all&checkall=1")
    fun deleteFavorites(
        @Field("formhash") formhash: String,
        @Field("delfavorite") delFavorite: String = "true",  // 把两个可能的确认参数都带上
        @Field("deletesubmit") deleteSubmit: String = "true",
        @Field("favorite[]") favIds: List<String>
    ): Call<ResponseBody>
}