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
import java.util.concurrent.ConcurrentHashMap

private interface SignApi {
    @GET
    suspend fun fetchHtml(@Url url: String): ResponseBody
}

/**
 * 后台自动签到 - 账号隔离版
 */
object AutoSignManager {
    private const val BASE_URL = "https://bbs.yamibo.com/"
    private const val SIGN_PAGE_URL = "https://bbs.yamibo.com/plugin.php?id=zqlj_sign&mobile=2"

    private val memorySignMap = ConcurrentHashMap<Int, String>()

    private fun getServerToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
        return sdf.format(Date())
    }

    private fun getCurrentAccountHash(): Int? {
        val cookie = GlobalData.currentCookie
        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(cookie)
        return authMatch?.groupValues?.get(1)?.hashCode()
    }

    private fun getSignKey(): androidx.datastore.preferences.core.Preferences.Key<String>? {
        val hash = getCurrentAccountHash() ?: return null
        return stringPreferencesKey("last_sign_date_$hash")
    }

    /**
     * 前置检查：判断当前 Hash 今日是否已完成签到流程
     */
    suspend fun needsSignIn(): Boolean {
        val today = getServerToday()
        val accountHash = getCurrentAccountHash() ?: return true

        if (memorySignMap[accountHash] == today) return false

        val signKey = getSignKey() ?: return true
        val prefs = GlobalData.dataStore?.data?.first()
        val lastSign = prefs?.get(signKey) ?: ""

        return lastSign != today
    }

    /**
     * 严格状态校验：检查页面是否确实包含“今日已打卡”的唯一标识
     */
    private suspend fun verifySignInStatus(): Boolean {
        return try {
            val api = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val pageHtml = api.fetchHtml(SIGN_PAGE_URL).string()
            pageHtml.contains("""class="btna">今日已打卡</a>""")
        } catch (_: Exception) {
            false
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val today = getServerToday()
        val accountHash = getCurrentAccountHash() ?: return@withContext

        // 1. 内存及持久化预判
        if (!force && memorySignMap[accountHash] == today) return@withContext

        val signKey = getSignKey() ?: return@withContext
        val prefs = GlobalData.dataStore?.data?.first()
        if (!force && prefs?.get(signKey) == today) {
            memorySignMap[accountHash] = today
            return@withContext
        }

        // 2. 进场摸底检查
        if (verifySignInStatus()) {
            memorySignMap[accountHash] = today
            GlobalData.dataStore?.edit { it[signKey] = today }
            if (force) showToast(context, "今日已打卡")
            return@withContext
        }

        // 3. 执行核心签到流程
        val (success, msg) = performAutoSign()
        if (success) {
            if (msg == "今日已打卡") {
                memorySignMap[accountHash] = today
                GlobalData.dataStore?.edit { it[signKey] = today }
                return@withContext
            }

            // 4. 签到后延迟校验（确保服务器落库）
            delay(3000L)
            if (verifySignInStatus()) {
                memorySignMap[accountHash] = today
                GlobalData.dataStore?.edit { it[signKey] = today }
                showToast(context, "签到成功")
            } else {
                showToast(context, "签到未生效，请手动签到")
            }
        } else {
            showToast(context, if (force) msg else "签到未生效，请手动签到")
        }
    }

    private suspend fun performAutoSign(): Pair<Boolean, String> {
        try {
            val api = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val pageHtml = api.fetchHtml(SIGN_PAGE_URL).string()

            if (pageHtml.contains("""class="btna">今日已打卡</a>""")) {
                return Pair(true, "今日已打卡")
            }

            val regex = """href="(plugin\.php\?id=zqlj_sign(?:&amp;|&)sign=[a-zA-Z0-9]+)"""".toRegex()
            val matchResult = regex.find(pageHtml)

            if (matchResult != null && pageHtml.contains("""class="btna">点击打卡</a>""")) {
                val path = matchResult.groupValues[1].replace("&amp;", "&")
                api.fetchHtml(BASE_URL + path).string()
                return Pair(true, "打卡请求已发送")
            }
            return Pair(false, "解析异常")
        } catch (_: Exception) {
            return Pair(false, "网络异常")
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