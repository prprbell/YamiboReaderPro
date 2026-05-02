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
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
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

enum class SignTrigger {
    LAUNCH, RESUME
}

/**
 * 后台自动签到
 */
object AutoSignManager {
    private const val BASE_URL = "https://bbs.yamibo.com/"
    private const val MAX_DAILY_RETRIES = 2

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

    private fun getQuotaKey(hash: Int) = stringPreferencesKey("sign_quota_v3_$hash")

    private suspend fun getCurrentQuota(hash: Int): Triple<String, Int, Int> {
        val prefs = GlobalData.dataStore?.data?.first()
        val rawData = prefs?.get(getQuotaKey(hash)) ?: ""
        val parts = rawData.split(":")
        val date = if (parts.isNotEmpty()) parts[0] else ""
        val launchCount = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        val resumeCount = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
        return Triple(date, launchCount, resumeCount)
    }

    private suspend fun updateQuota(hash: Int, date: String, launchCount: Int, resumeCount: Int) {
        GlobalData.dataStore?.edit { it[getQuotaKey(hash)] = "$date:$launchCount:$resumeCount" }
    }

    suspend fun resetQuota(hash: Int? = getCurrentAccountHash()) {
        if (hash == null) return
        val today = getServerToday()
        updateQuota(hash, today, 0, 0)
    }

    suspend fun needsSignIn(trigger: SignTrigger = SignTrigger.LAUNCH): Boolean {
        val accountHash = getCurrentAccountHash() ?: return false
        val today = getServerToday()
        val (savedDate, launchCount, resumeCount) = getCurrentQuota(accountHash)

        if (savedDate != today) return true

        return when (trigger) {
            SignTrigger.LAUNCH -> launchCount < MAX_DAILY_RETRIES
            SignTrigger.RESUME -> resumeCount < MAX_DAILY_RETRIES
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(
        context: Context,
        trigger: SignTrigger = SignTrigger.LAUNCH,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val accountHash = getCurrentAccountHash() ?: return@withContext
        val today = getServerToday()
        var (savedDate, launchCount, resumeCount) = getCurrentQuota(accountHash)

        if (today != savedDate) {
            launchCount = 0
            resumeCount = 0
        }

        if (!force) {
            val currentCount = if (trigger == SignTrigger.LAUNCH) launchCount else resumeCount
            if (currentCount >= MAX_DAILY_RETRIES) return@withContext
        }

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

            val signApi = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val signUrl = "${BASE_URL}plugin.php?id=zqlj_sign&sign=$formHash"
            val actionResponseHtml = signApi.fetchHtml(signUrl).string()

            if (!force) {
                if (trigger == SignTrigger.LAUNCH) launchCount++ else resumeCount++
                updateQuota(accountHash, today, launchCount, resumeCount)
            }

            if (actionResponseHtml.contains("已经打过卡了") ||
                actionResponseHtml.contains("今日已打卡") ||
                actionResponseHtml.contains("重复操作")
            ) {
                if (force) showToast(context, "今日已打卡")
            } else if (actionResponseHtml.contains("打卡成功") ||
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
                delay(1200L)
                toast.cancel()
            }
        }
    }
}