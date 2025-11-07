package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.shirakawatyu.yamibo.novel.global.GlobalData

class CookieUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo")

        fun getCookie(callback: (cookie: String) -> Unit) {
            DataStoreUtil.getData(key, callback, onNull = {
                callback("")
            })
        }

        fun getCookieFlow(): Flow<String> {
            val dataStore =
                GlobalData.dataStore ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data
                .map { preferences ->
                    preferences[key] ?: ""
                }
        }

        fun saveCookie(cookie: String) {
            GlobalData.currentCookie = cookie
            DataStoreUtil.addData(cookie, key)
        }
    }
}