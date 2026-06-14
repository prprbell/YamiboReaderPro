package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import kotlinx.coroutines.delay
import org.shirakawatyu.yamibo.novel.global.GlobalData

object AccountSyncManager {
    private const val YAMIBO_COOKIE_URL = "https://bbs.yamibo.com"
    private val AUTH_COOKIE_REGEX = Regex("EeqY_2132_auth=([^;]+)")

    private var previousAuthHash: Int? = null
    private var isFirstCheck = true
    val authStateFlow = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /**
     * 从 WebView CookieManager 同步论坛 Cookie，并在登录账号变化时发出 authStateFlow。
     *
     * 说明：
     * 1. 首次检查也会保存 Cookie，避免"已经登录但 DataStore 还是空"的情况。
     * 2. source 只用于日志/调试扩展，当前不参与逻辑。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun syncCookieAndCheckSign(context: Context, source: String = "") {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val currentCookie = cookieManager.getCookie(YAMIBO_COOKIE_URL) ?: ""
        val currentHash = AUTH_COOKIE_REGEX.find(currentCookie)
            ?.groupValues
            ?.getOrNull(1)
            ?.hashCode()

        if (isFirstCheck) {
            isFirstCheck = false
            previousAuthHash = currentHash

            if (currentHash != null) {
                CookieUtil.saveCookie(currentCookie)
                authStateFlow.value = currentHash

                if (GlobalData.isAutoSignInEnabled.value) {
                    val needsSign = AutoSignManager.needsSignIn()
                    if (needsSign) {
                        delay(1000L)
                        AutoSignManager.checkAndSignIfNeeded(context, force = false)
                    }
                }
            } else {
                CookieUtil.saveCookie("")
                authStateFlow.value = null
            }
            return
        }

        if (currentHash != previousAuthHash) {
            previousAuthHash = currentHash

            if (currentHash != null) {
                CookieUtil.saveCookie(currentCookie)
                authStateFlow.value = currentHash
                AutoSignManager.resetQuota()

                if (GlobalData.isAutoSignInEnabled.value) {
                    delay(3000L)
                    AutoSignManager.checkAndSignIfNeeded(context, force = false)
                }
            } else {
                CookieUtil.saveCookie("")
                authStateFlow.value = null
            }
        }
    }
}
