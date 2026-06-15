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
        private val autoClearCacheKey = stringPreferencesKey("auto_clear_cache")
        private val dnsEnabledKey = stringPreferencesKey("dns_optimization_enabled")
        private val dnsModeKey = stringPreferencesKey("dns_optimization_mode")
        private val darkModeKey = stringPreferencesKey("dark_mode")
        private val darkModeThemeKey = stringPreferencesKey("dark_mode_theme")
        private val lightModeThemeKey = stringPreferencesKey("light_mode_theme")
        private val customDnsUrlKey = stringPreferencesKey("custom_dns_url")
        private val skipVersionKey = stringPreferencesKey("skip_version")
        private val attachmentDownloadTargetKey = stringPreferencesKey("attachment_download_target")
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
        fun saveAutoClearCacheMode(isEnabled: Boolean) {
            DataStoreUtil.addData(isEnabled.toString(), autoClearCacheKey)
        }

        fun getAutoClearCacheMode(callback: (Boolean) -> Unit) {
            DataStoreUtil.getData(autoClearCacheKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
        fun saveDnsOptimizationEnabled(enabled: Boolean) {
            DataStoreUtil.addData(enabled.toString(), dnsEnabledKey)
        }
        fun getDnsOptimizationEnabled(callback: (Boolean) -> Unit) {
            DataStoreUtil.getData(dnsEnabledKey, callback = { value ->
                callback(value.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                DataStoreUtil.getData(customDnsKey, callback = { oldValue ->
                    callback(oldValue.toBooleanStrictOrNull() ?: false)
                }, onNull = { callback(false) })
            })
        }
        fun saveDnsOptimizationMode(mode: String) {
            DataStoreUtil.addData(mode, dnsModeKey)
        }
        fun getDnsOptimizationMode(callback: (String) -> Unit) {
            DataStoreUtil.getData(dnsModeKey, callback = { value ->
                callback(value.ifBlank { "auto" })
            }, onNull = { callback("auto") })
        }
        fun saveCustomDnsUrl(url: String) {
            DataStoreUtil.addData(url, customDnsUrlKey)
        }
        fun getCustomDnsUrl(callback: (String) -> Unit) {
            DataStoreUtil.getData(customDnsUrlKey, callback = { value ->
                callback(value)
            }, onNull = {
                callback("")
            })
        }
        fun saveDarkModeTheme(themeId: Int) {
            DataStoreUtil.addData(themeId.toString(), darkModeThemeKey)
        }
        fun getDarkModeTheme(callback: (Int) -> Unit) {
            DataStoreUtil.getData(darkModeThemeKey, callback = {
                callback(it.toIntOrNull() ?: 0)
            }, onNull = {
                callback(0)
            })
        }
        fun saveDarkMode(enabled: Boolean) {
            DataStoreUtil.addData(enabled.toString(), darkModeKey)
        }
        fun getDarkMode(callback: (Boolean) -> Unit) {
            DataStoreUtil.getData(darkModeKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
        }
        fun saveLightModeTheme(themeId: Int) {
            DataStoreUtil.addData(themeId.toString(), lightModeThemeKey)
        }
        fun getLightModeTheme(callback: (Int) -> Unit) {
            DataStoreUtil.getData(lightModeThemeKey, callback = {
                callback(it.toIntOrNull() ?: 0)
            }, onNull = {
                callback(0)
            })
        }
        fun saveHistoryMaxCount(count: Int) {
            DataStoreUtil.addData(count.toString(), stringPreferencesKey("history_max_count"))
        }

        fun getHistoryMaxCount(callback: (Int) -> Unit) {
            DataStoreUtil.getData(stringPreferencesKey("history_max_count"), callback = {
                callback(it.toIntOrNull() ?: 500)
            }, onNull = {
                callback(500)
            })
        }

        fun saveSkipVersion(version: String) {
            DataStoreUtil.addData(version, skipVersionKey)
        }
        fun getSkipVersion(callback: (String) -> Unit) {
            DataStoreUtil.getData(skipVersionKey, callback = {
                callback(it)
            }, onNull = {
                callback("")
            })
        }

        fun saveLastUpdateCheckTime(millis: Long) {
            DataStoreUtil.addData(millis.toString(), stringPreferencesKey("last_update_check"))
        }
        fun getLastUpdateCheckTime(callback: (Long) -> Unit) {
            DataStoreUtil.getData(stringPreferencesKey("last_update_check"), callback = {
                callback(it.toLongOrNull() ?: 0L)
            }, onNull = {
                callback(0L)
            })
        }

        fun saveAttachmentDownloadTarget(target: String) {
            DataStoreUtil.addData(target, attachmentDownloadTargetKey)
        }

        fun getAttachmentDownloadTarget(callback: (String) -> Unit) {
            DataStoreUtil.getData(attachmentDownloadTargetKey, callback = {
                callback(it)
            }, onNull = {
                callback("")
            })
        }

        fun clearAttachmentDownloadTarget() {
            DataStoreUtil.addData("", attachmentDownloadTargetKey)
        }
    }
}
