package org.shirakawatyu.yamibo.novel.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi

object FavoriteDeleteUtil {
    private const val TAG = "FavoriteDelete"

    /**
     * 批量删除远端收藏
     * @param prefetchFormHash 预先抓取的防 CSRF 校验码
     * @param favIds 要删除的收藏 ID 列表
     * @return 删除是否成功
     */
    suspend fun deleteFavoritesBatch(prefetchFormHash: String?, favIds: List<String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "==== 开始执行远端删除 ====")
                Log.d(TAG, "准备删除的 IDs: $favIds")
                Log.d(TAG, "预加载的 formHash: $prefetchFormHash")

                val api = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)

                var formHash = prefetchFormHash

                // 如果预取失败，重新抓取
                if (formHash.isNullOrEmpty()) {
                    Log.d(TAG, "预取 formHash 为空，尝试实时抓取...")
                    val faqResponse = api.getFormHash().execute()
                    val html = faqResponse.body()?.string() ?: ""
                    val formHashMatch = Regex("""formhash=([a-zA-Z0-9]{8})""").find(html)
                    formHash = formHashMatch?.groupValues?.get(1)
                    Log.d(TAG, "实时抓取到的 formHash: $formHash")
                }

                if (formHash.isNullOrEmpty()) {
                    Log.e(TAG, "致命错误：未能获取到 formHash，请求终止！")
                    return@withContext false
                }

                Log.d(TAG, "发射 POST 请求...")
                val response = api.deleteFavorites(
                    formhash = formHash,
                    favIds = favIds
                ).execute()

                // 打印服务器的真实响应
                val responseBody = if (response.isSuccessful) {
                    response.body()?.string()
                } else {
                    response.errorBody()?.string()
                }

                Log.d(TAG, "HTTP 状态码: ${response.code()}")
                Log.d(TAG, "服务器返回内容 (截取前500字): ${responseBody?.take(500)}")

                // 如果返回内容里包含了“删除成功”或“操作成功”，通常意味着 Discuz 处理成功了
                val isActuallySuccess = response.isSuccessful &&
                        (responseBody?.contains("成功") == true || responseBody?.contains("succeed") == true)

                Log.d(TAG, "最终判定是否成功: $isActuallySuccess")
                Log.d(TAG, "============================")

                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "请求发生异常", e)
                e.printStackTrace()
                false
            }
        }
    }
}