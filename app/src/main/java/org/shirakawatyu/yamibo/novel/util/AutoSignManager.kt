package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private interface SignApi {
    @GET
    suspend fun fetchHtml(@Url url: String): ResponseBody
}

/**
 * 后台自动签到
 * 每日严格执行最多8次网络探测。
 */
object AutoSignManager {
    private const val BASE_URL = "https://bbs.yamibo.com/"
    private const val SIGN_PAGE_URL = "https://bbs.yamibo.com/plugin.php?id=zqlj_sign&mobile=2"

    private const val MAX_DAILY_RETRIES = 8

    private fun getServerToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
        return sdf.format(Date())
    }

    private fun getCurrentAccountHash(): Int? {
        val cookie = GlobalData.currentCookie ?: return null
        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(cookie)
        return authMatch?.groupValues?.get(1)?.hashCode()
    }

    private fun getQuotaKey(hash: Int) = stringPreferencesKey("sign_quota_v2_$hash")

    private suspend fun getCurrentQuota(hash: Int): Pair<String, Int> {
        val prefs = GlobalData.dataStore?.data?.first()
        val rawData = prefs?.get(getQuotaKey(hash)) ?: ""
        val parts = rawData.split(":")
        val date = if (parts.isNotEmpty()) parts[0] else ""
        val count = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        return Pair(date, count)
    }

    private suspend fun updateQuota(hash: Int, date: String, count: Int) {
        GlobalData.dataStore?.edit { it[getQuotaKey(hash)] = "$date:$count" }
    }

    suspend fun needsSignIn(): Boolean {
        val accountHash = getCurrentAccountHash() ?: return false
        val today = getServerToday()
        val (savedDate, count) = getCurrentQuota(accountHash)

        if (savedDate != today) return true

        // 只要没到10次，永远放行
        return count < MAX_DAILY_RETRIES
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val accountHash = getCurrentAccountHash() ?: return@withContext
        val today = getServerToday()
        var (_, currentCount) = getCurrentQuota(accountHash)

        if (today != getCurrentQuota(accountHash).first) {
            currentCount = 0
        }

        if (!force && currentCount >= MAX_DAILY_RETRIES) return@withContext

        try {
            if (!force) {
                currentCount++
                updateQuota(accountHash, today, currentCount)
            }

            val api = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val pageHtml = api.fetchHtml(SIGN_PAGE_URL).string()

            if (pageHtml.contains("""class="btna">今日已打卡</a>""")) {
                if (force) showToast(context, "今日已打卡")
                return@withContext
            }

            // 4. 执行签到动作
            val regex = """href="(plugin\.php\?id=zqlj_sign(?:&amp;|&)sign=[a-zA-Z0-9]+)"""".toRegex()
            val matchResult = regex.find(pageHtml)

            if (matchResult != null) {
                val path = matchResult.groupValues[1].replace("&amp;", "&")
                val actionResponseHtml = api.fetchHtml(BASE_URL + path).string()

                if (actionResponseHtml.contains("成功") || actionResponseHtml.contains("打卡") || actionResponseHtml.contains("提示信息")) {
                    showToast(context, "签到成功")
                } else {
                    if (force) showToast(context, "打卡请求已发送，请稍后确认")
                }
            } else {
                if (force) showToast(context, "HTML结构不匹配，无法打卡")
            }
        } catch (e: Exception) {
            if (force) showToast(context, "网络异常，稍后重试")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun showToast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            val toast = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT)
            toast.show()
            GlobalScope.launch(Dispatchers.Main) {
                delay(1200L)
                toast.cancel()
            }
        }
    }
}