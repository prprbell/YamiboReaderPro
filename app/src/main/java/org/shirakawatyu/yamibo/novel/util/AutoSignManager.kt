package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
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

    private val LAST_SIGN_DATE_KEY = stringPreferencesKey("last_sign_date")

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun checkAndSignIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val prefs = GlobalData.dataStore?.data?.first()
        val lastSignDate = prefs?.get(LAST_SIGN_DATE_KEY) ?: ""

        if (lastSignDate == today) {
            Log.i(TAG, "今天 ($today) 已打卡")
            return@withContext
        }

        val (success, msg) = performAutoSign()

        if (success) {
            GlobalData.dataStore?.edit { preferences ->
                preferences[LAST_SIGN_DATE_KEY] = today
            }
            withContext(Dispatchers.Main) {
                val toast = Toast.makeText(context, "签到成功", Toast.LENGTH_SHORT)
                toast.show()

                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(1200L)
                    toast.cancel()
                }
            }
        } else {
            Log.w(TAG, "签到未成功 ($msg)")
        }
    }

    /**
     * 核心签到逻辑
     * @return Pair<Boolean, String> 返回 <是否成功/已签到, 提示信息>
     */
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
                    Pair(true, "打卡成功")
                } else {
                    Pair(true, "打卡请求已发送")
                }
            } else {
                return Pair(true, "确认已完成打卡 (未检测到打卡按钮)")
            }
        } catch (_: Exception) {
            return Pair(false, "网络或解析异常")
        }
    }
}