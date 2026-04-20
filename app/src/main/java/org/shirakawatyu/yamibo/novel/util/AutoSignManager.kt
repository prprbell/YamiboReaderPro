package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.util.Log
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
 */
object AutoSignManager {
    private const val TAG = "AutoSignManager"
    private const val BASE_URL = "https://bbs.yamibo.com/"
    private const val SIGN_PAGE_URL = "https://bbs.yamibo.com/plugin.php?id=zqlj_sign&mobile=2"

    private var memorySignDate = ""

    private fun getServerToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
        return sdf.format(Date())
    }

    private fun getSignKey(): androidx.datastore.preferences.core.Preferences.Key<String>? {
        val cookie = GlobalData.currentCookie
        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(cookie)
        val authString = authMatch?.groupValues?.get(1) ?: return null
        return stringPreferencesKey("last_sign_date_${authString.hashCode()}")
    }

    /**
     * 前置预判：今天是否需要签到？
     * 只要本地严格证明今天签过，就直接放行。
     */
    suspend fun needsSignIn(): Boolean {
        val today = getServerToday()

        if (memorySignDate == today) return false

        val signKey = getSignKey() ?: return true
        val prefs = GlobalData.dataStore?.data?.first()
        val lastSign = prefs?.get(signKey) ?: ""

        return lastSign != today
    }

    /**
     * 独立网络状态抓取：严格校验此刻是否显示已打卡
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

        // 已经签到过则直接返回
        if (!force && memorySignDate == today) return@withContext

        val signKey = getSignKey() ?: return@withContext
        val prefs = GlobalData.dataStore?.data?.first()
        val lastSign = prefs?.get(signKey) ?: ""

        if (!force && lastSign == today) {
            memorySignDate = today
            return@withContext
        }

        // ==========================================
        // 核心流程：摸底 -> 签到 -> 签到后严格校验
        // ==========================================

        // 1. 摸底查一次：也许用户在电脑上签过了，或者上一次网络波动导致虽然没记上但其实成功了
        val preVerify = verifySignInStatus()
        if (preVerify) {
            memorySignDate = today
            GlobalData.dataStore?.edit { it[signKey] = today }
            if (force) showToast(context, "今日已打卡")
            return@withContext
        }

        // 2. 确认没签到，正式发送打卡请求
        val (success, msg) = performAutoSign()

        if (success) {
            if (msg == "今日已打卡") {
                memorySignDate = today
                GlobalData.dataStore?.edit { it[signKey] = today }
                if (force) showToast(context, msg)
                return@withContext
            }

            // 3. 发出请求后，等待服务器落库，进行签到后延迟校验
            delay(3000L)
            val postVerify = verifySignInStatus()

            if (postVerify) {
                // 校验通过，彻底稳了
                memorySignDate = today
                GlobalData.dataStore?.edit { it[signKey] = today }
                showToast(context, "签到成功")
            } else {
                // 校验失败（被吞了或者验证码拦截），严格不写入本地标记
                Log.w(TAG, "签到后校验: 状态未更新")
                showToast(context, "签到未生效，请手动签到")
            }
        } else {
            // 请求发送环节就出了错
            Log.w(TAG, "签到未成功 ($msg)")
            showToast(context, if (force) msg else "签到未生效，请手动签到")
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

    private suspend fun performAutoSign(): Pair<Boolean, String> {
        try {
            val cookie = GlobalData.currentCookie
            if (cookie.isEmpty() || !cookie.contains("EeqY_2132_auth=")) {
                return Pair(false, "账号未登录")
            }

            val api = YamiboRetrofit.getInstance().create(SignApi::class.java)
            val pageHtml = api.fetchHtml(SIGN_PAGE_URL).string()

            // 摸底防漏网之鱼
            if (pageHtml.contains("""class="btna">今日已打卡</a>""")) {
                return Pair(true, "今日已打卡")
            }

            val regex = """href="(plugin\.php\?id=zqlj_sign(?:&amp;|&)sign=[a-zA-Z0-9]+)"""".toRegex()
            val matchResult = regex.find(pageHtml)

            if (matchResult != null && pageHtml.contains("""class="btna">点击打卡</a>""")) {
                val path = matchResult.groupValues[1].replace("&amp;", "&")
                val finalSignUrl = BASE_URL + path

                // 仅仅发请求，完全不依赖这个请求返回的 HTML 里的文字
                api.fetchHtml(finalSignUrl).string()

                return Pair(true, "打卡请求已发送")
            } else {
                return Pair(false, "未检测到打卡按钮，页面解析异常")
            }
        } catch (_: Exception) {
            return Pair(false, "网络或请求异常")
        }
    }
}