package org.shirakawatyu.yamibo.novel.util.favorite

import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi

object FavoriteDeleteUtil {

    /**
     * 批量删除远端收藏
     * @param prefetchFormHash 预先抓取的防 CSRF 校验码
     * @param favIds 要删除的收藏 ID 列表
     * @return 删除是否成功
     */
    suspend fun deleteFavoritesBatch(prefetchFormHash: String?, favIds: List<String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val api = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)
                var formHash = prefetchFormHash
                if (formHash.isNullOrEmpty()) {
                    val profileResponse = api.getFormHash().execute()
                    val json = profileResponse.body()?.string() ?: ""
                    try {
                        val jsonObject = JSON.parseObject(json)
                        formHash = jsonObject?.getJSONObject("Variables")?.getString("formhash")
                    } catch (_: Exception) { }
                }
                if (formHash.isNullOrEmpty()) return@withContext false

                val response = api.deleteFavorites(
                    formhash = formHash,
                    favIds = favIds
                ).execute()

                val responseBody = if (response.isSuccessful) {
                    response.body()?.string()
                } else {
                    response.errorBody()?.string()
                }

                response.isSuccessful && (responseBody?.contains("成功") == true || responseBody?.contains(
                    "succeed"
                ) == true)
            } catch (e: Exception) {
                false
            }
        }
    }
}