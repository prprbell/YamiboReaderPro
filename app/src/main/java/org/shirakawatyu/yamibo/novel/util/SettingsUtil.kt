package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.shirakawatyu.yamibo.novel.bean.ReaderSettings
import org.shirakawatyu.yamibo.novel.global.GlobalData

/**
 * 设置管理工具
 * 负责保存和读取阅读器设置、省流模式开关以及折叠模式状态
 */
class SettingsUtil {
    companion object {
        private val key = stringPreferencesKey("settings")
        private val collapseModeKey = stringPreferencesKey("favorite_collapse_mode")
        private val homePageKey = stringPreferencesKey("home_page")
        private val customDnsKey = stringPreferencesKey("custom_dns_mode")
        private val clickToTopKey = stringPreferencesKey("click_to_top_mode")
        private val autoSignInKey = stringPreferencesKey("auto_sign_in")
        fun saveSettings(settings: ReaderSettings) {
            DataStoreUtil.addData(JSON.toJSONString(settings), key)
        }

        fun getSettings(callback: (settings: ReaderSettings) -> Unit, onNull: () -> Unit) {
            DataStoreUtil.getData(key, callback = {
                try {
                    val settings = JSON.parseObject(it, ReaderSettings::class.java)
                    callback(settings)
                } catch (_: JSONException) {
                    onNull()
                }
            }, onNull = onNull)
        }


        fun saveFavoriteCollapseMode(isCollapsed: Boolean) {
            DataStoreUtil.addData(isCollapsed.toString(), collapseModeKey)
        }

        fun getFavoriteCollapseMode(callback: (isCollapsed: Boolean) -> Unit) {
            DataStoreUtil.getData(collapseModeKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
        fun saveHomePage(route: String) {
            DataStoreUtil.addData(route, homePageKey)
        }

        fun getHomePage(callback: (route: String) -> Unit) {
            DataStoreUtil.getData(homePageKey, callback = {
                callback(it.ifBlank { "BBSPage" })
            }, onNull = {
                callback("BBSPage")
            })
        }
        fun saveCustomDnsMode(isEnabled: Boolean) {
            DataStoreUtil.addData(isEnabled.toString(), customDnsKey)
        }

        fun getCustomDnsMode(callback: (isEnabled: Boolean) -> Unit) {
            DataStoreUtil.getData(customDnsKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
        fun saveClickToTopMode(isEnabled: Boolean) {
            DataStoreUtil.addData(isEnabled.toString(), clickToTopKey)
        }

        fun getClickToTopMode(callback: (isEnabled: Boolean) -> Unit) {
            DataStoreUtil.getData(clickToTopKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
        fun saveAutoSignInMode(isEnabled: Boolean) {
            DataStoreUtil.addData(isEnabled.toString(), autoSignInKey)
        }

        fun getAutoSignInMode(callback: (Boolean) -> Unit) {
            DataStoreUtil.getData(autoSignInKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
    }
}