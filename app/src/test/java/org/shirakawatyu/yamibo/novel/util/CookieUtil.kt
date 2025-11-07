package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey

class CookieUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo")

        fun getCookie(callback: (cookie: String) -> Unit) {
            DataStoreUtil.getData(key, callback)
        }

        fun saveCookie(cookie: String) {
            DataStoreUtil.addData(cookie, key)
        }
    }
}