package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.global.GlobalData

// 数据存储工具类
class DataStoreUtil {
    companion object {
        fun addData(data: String, key: Preferences.Key<String>, callback: () -> Unit = {}) {
            CoroutineScope(Dispatchers.IO).launch {
                GlobalData.dataStore?.edit {
                    it[key] = data
                }
                withContext(Dispatchers.Main) {
                    callback()
                }
            }
        }

        fun getData(
            key: Preferences.Key<String>,
            callback: (data: String) -> Unit,
            onNull: () -> Unit = {}
        ) {
            val dataFlow: Flow<String?>? = GlobalData.dataStore?.data?.map { pref ->
                pref[key]
            }
            CoroutineScope(Dispatchers.IO).launch {
                val data = dataFlow?.firstOrNull()
                withContext(Dispatchers.Main) {
                    if (data != null) {
                        callback(data)
                    } else {
                        onNull()
                    }
                }
            }
        }
    }
}