package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException
import org.shirakawatyu.yamibo.novel.bean.ReaderSettings

/**
 * 设置管理工具
 * 负责保存和读取阅读器设置、省流模式开关以及折叠模式状态
 */
class SettingsUtil {
    companion object {
        private val key = stringPreferencesKey("settings")
        private val dataSaverKey = stringPreferencesKey("data_saver_mode")
        private val collapseModeKey = stringPreferencesKey("favorite_collapse_mode")
        private val homePageKey = stringPreferencesKey("home_page")
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

        fun saveDataSaverMode(isDataSaver: Boolean) {
            DataStoreUtil.addData(isDataSaver.toString(), dataSaverKey)
        }

        fun getDataSaverMode(callback: (isDataSaver: Boolean) -> Unit) {
            DataStoreUtil.getData(dataSaverKey, callback = {
                callback(it.toBooleanStrictOrNull() ?: false)
            }, onNull = {
                callback(false)
            })
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
    }
}