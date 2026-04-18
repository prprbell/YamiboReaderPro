package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import org.shirakawatyu.yamibo.novel.global.GlobalData

object AccountSyncManager {
    private var previousAuthHash: Int? = null
    private var isFirstCheck = true
    val authStateFlow = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /**
     * 每次调用时，去 CookieManager 查一下有没有换账号。
     * @param context 上下文
     * @param source 触发来源（仅用于调试日志）
     */
    suspend fun syncCookieAndCheckSign(context: Context, source: String = "") {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val currentCookie = cookieManager.getCookie("https://bbs.yamibo.com") ?: ""

        val authMatch = Regex("EeqY_2132_auth=([^;]+)").find(currentCookie)
        val currentHash = authMatch?.groupValues?.get(1)?.hashCode()

        if (isFirstCheck) {
            isFirstCheck = false
            previousAuthHash = currentHash
            authStateFlow.value = currentHash
            if (currentHash != null) {
                GlobalData.currentCookie = currentCookie
                if (GlobalData.isAutoSignInEnabled.value) {
                    val needsSign = AutoSignManager.needsSignIn()
                    if (needsSign) {
                        kotlinx.coroutines.delay(1000L)
                        AutoSignManager.checkAndSignIfNeeded(context, force = false)
                    }
                }
            }
            return
        }

        if (currentHash != previousAuthHash) {
            previousAuthHash = currentHash
            authStateFlow.value = currentHash
            if (currentHash != null) {
                GlobalData.currentCookie = currentCookie
                CookieUtil.saveCookie(currentCookie)

                if (GlobalData.isAutoSignInEnabled.value) {
                    kotlinx.coroutines.delay(2000L)
                    AutoSignManager.checkAndSignIfNeeded(context, force = false)
                }
            } else {
                GlobalData.currentCookie = ""
                CookieUtil.saveCookie("")
            }
        }
    }
}