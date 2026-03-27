package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException
import org.shirakawatyu.yamibo.novel.bean.ReaderSettings

/**
 * 设置管理工具
 * 负责保存和读取阅读器设置和省流模式开关
 */
class SettingsUtil {
    companion object {
        private val key = stringPreferencesKey("settings")
        private val dataSaverKey = stringPreferencesKey("data_saver_mode")
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
    }
}