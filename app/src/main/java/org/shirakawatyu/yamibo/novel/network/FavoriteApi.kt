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

    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @GET("/misc.php?mod=faq")
    fun getFormHash(): Call<ResponseBody>

    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    @FormUrlEncoded
    @POST("/home.php?mod=spacecp&ac=favorite&op=delete&type=all&checkall=1")
    fun deleteFavorites(
        @Field("formhash") formhash: String,
        @Field("delfavorite") delFavorite: String = "true",
        @Field("deletesubmit") deleteSubmit: String = "true",
        @Field("favorite[]") favIds: List<String>
    ): Call<ResponseBody>
}