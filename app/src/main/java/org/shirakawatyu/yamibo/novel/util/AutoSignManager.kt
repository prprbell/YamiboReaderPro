package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import retrofit2.http.GET
import retrofit2.http.Url

private interface SignApi {
    @GET
    suspend fun fetchHtml(@Url url: String): ResponseBody
}

/**
 * 后台自动签到
 */
object AutoSignManager {
    private const val BASE_URL = "https://bbs.yamibo.com/"

    private fun getCurrentAccountHash(): Int? {
        val cookie = GlobalData.currentCookie
        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(cookie)
        return authMatch?.groupValues?.get(1)?.hashCode()
    }

    suspend fun needsSignIn(): Boolean {
        return getCurrentAccountHash() != null
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(context: Context, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            val accountHash = getCurrentAccountHash() ?: return@withContext

            try {
                val favoriteApi = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)
                val faqResponse = favoriteApi.getFormHash().execute()
                val faqHtml = faqResponse.body()?.string() ?: ""

                val match1 = Regex("""name="formhash"\s+value="([^"]+)"""").find(faqHtml)
                val match2 = Regex("""formhash=([a-zA-Z0-9]{8})""").find(faqHtml)
                val formHash = match1?.groupValues?.get(1) ?: match2?.groupValues?.get(1)

                if (formHash.isNullOrEmpty()) {
                    if (force) showToast(context, "获取鉴权失败，无法打卡")
                    return@withContext
                }

                // 打卡
                val signApi = YamiboRetrofit.getInstance().create(SignApi::class.java)
                val signUrl = "${BASE_URL}plugin.php?id=zqlj_sign&sign=$formHash"
                val actionResponseHtml = signApi.fetchHtml(signUrl).string()

                // 优先拦截重复打卡
                if (actionResponseHtml.contains("已经打过卡了") ||
                    actionResponseHtml.contains("今日已打卡") ||
                    actionResponseHtml.contains("重复操作")
                ) {
                    if (force) showToast(context, "今日已打卡")
                }
                // 严格匹配成功字眼
                else if (actionResponseHtml.contains("打卡成功") ||
                    actionResponseHtml.contains("成功") ||
                    actionResponseHtml.contains("获得了")
                ) {
                    showToast(context, "签到成功")
                } else {
                    if (force) showToast(context, "打卡请求已发送")
                }
            } catch (_: Exception) {
                if (force) showToast(context, "网络异常，稍后重试")
            }
        }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun showToast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            val toast = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT)
            toast.show()
            GlobalScope.launch(Dispatchers.Main) {
                delay(1000L)
                toast.cancel()
            }
        }
    }
}