package org.shirakawatyu.yamibo.novel.util

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
                    val faqResponse = api.getFormHash().execute()
                    val html = faqResponse.body()?.string() ?: ""

                    val match1 = Regex("""name="formhash"\s+value="([^"]+)"""").find(html)
                    val match2 = Regex("""formhash=([a-zA-Z0-9]+)""").find(html)
                    formHash = match1?.groupValues?.get(1) ?: match2?.groupValues?.get(1)
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

                response.isSuccessful && (responseBody?.contains("成功") == true || responseBody?.contains("succeed") == true)
            } catch (e: Exception) {
                false
            }
        }
    }
}