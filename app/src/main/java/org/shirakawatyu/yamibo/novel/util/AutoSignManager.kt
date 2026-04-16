package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
 * 后台自动签到-自用
 */
object AutoSignManager {
    private const val TAG = "AutoSignManager"
    private const val BASE_URL = "https://bbs.yamibo.com/"
    private const val SIGN_PAGE_URL = "https://bbs.yamibo.com/plugin.php?id=zqlj_sign&mobile=2"

    private var memorySignSuccessDate = ""

    private fun getServerToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
        return sdf.format(Date())
    }

    private fun getSignKeyForCurrentAccount(): androidx.datastore.preferences.core.Preferences.Key<String>? {
        val cookie = GlobalData.currentCookie
        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(cookie)
        val authString = authMatch?.groupValues?.get(1) ?: return null
        return stringPreferencesKey("last_sign_date_${authString.hashCode()}")
    }

    /**
     * 前置预判：今天是否还需要签到？
     * 利用内存和 DataStore 文件缓存极速判定，无需建立任何网络连接
     */
    suspend fun needsSignIn(): Boolean {
        val today = getServerToday()

        if (memorySignSuccessDate == today) return false

        val signKey = getSignKeyForCurrentAccount() ?: return true

        val prefs = GlobalData.dataStore?.data?.first()
        val lastSignDate = prefs?.get(signKey) ?: ""

        return lastSignDate != today
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val serverToday = getServerToday()

        if (!force && memorySignSuccessDate == serverToday) {
            return@withContext
        }

        val signKey = getSignKeyForCurrentAccount() ?: return@withContext

        val prefs = GlobalData.dataStore?.data?.first()
        val lastSignDate = prefs?.get(signKey) ?: ""

        if (!force && lastSignDate == serverToday) {
            memorySignSuccessDate = serverToday
            return@withContext
        }

        val (success, msg) = performAutoSign()

        if (success) {
            memorySignSuccessDate = serverToday
            GlobalData.dataStore?.edit { preferences ->
                preferences[signKey] = serverToday
            }

            if (msg == "签到成功" || msg == "打卡请求已发送" || (force && msg == "今日已打卡")) {
                withContext(Dispatchers.Main) {
                    val toast = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT)
                    toast.show()

                    GlobalScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(1200L)
                        toast.cancel()
                    }
                }
            } else {
                Log.i(TAG, "自动签到检查: $msg")
            }
        } else {
            Log.w(TAG, "签到判定未成功，留待下次重试 ($msg)")
        }
    }

    private suspend fun performAutoSign(): Pair<Boolean, String> {
        try {
            val cookie = GlobalData.currentCookie
            if (cookie.isEmpty() || !cookie.contains("EeqY_2132_auth=")) {
                return Pair(false, "账号未登录")
            }

            val api = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val pageHtml = api.fetchHtml(SIGN_PAGE_URL).string()

            val regex = """href="(plugin\.php\?id=zqlj_sign(?:&amp;|&)sign=[a-zA-Z0-9]+)"""".toRegex()
            val matchResult = regex.find(pageHtml)

            if (matchResult != null && pageHtml.contains("""class="btna">点击打卡</a>""")) {
                val path = matchResult.groupValues[1].replace("&amp;", "&")
                val finalSignUrl = BASE_URL + path
                val resultHtml = api.fetchHtml(finalSignUrl).string()

                return if (resultHtml.contains("成功") || resultHtml.contains("今日已打卡")) {
                    Pair(true, "签到成功")
                } else {
                    Pair(true, "打卡请求已发送")
                }
            }
            else if (pageHtml.contains("""class="btna">今日已打卡</a>""") ||
                pageHtml.contains(">今日已打卡</a>")) {

                return Pair(true, "今日已打卡")
            }
            else {
                return Pair(false, "未检测到打卡按钮，页面解析异常")
            }
        } catch (_: Exception) {
            return Pair(false, "网络或请求异常")
        }
    }
}