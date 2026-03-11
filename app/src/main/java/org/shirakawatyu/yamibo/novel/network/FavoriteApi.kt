package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query


interface FavoriteApi {
    @GET("/home.php?mod=space&do=favorite&view=me&type=thread&mobile=2")
    fun getFavoritePage(@Query("page") page: Int): Call<ResponseBody>
}