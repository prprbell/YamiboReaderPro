package org.shirakawatyu.yamibo.novel.network

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET


interface FavoriteApi {
    @GET("/home.php?mod=space&do=favorite&view=me&type=thread")
    fun getFavoritePage(): Call<ResponseBody>
}