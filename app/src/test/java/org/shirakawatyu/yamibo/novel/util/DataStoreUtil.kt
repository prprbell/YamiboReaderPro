package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.global.GlobalData

class DataStoreUtil {
    companion object {
        fun addData(data: String, key: Preferences.Key<String>, callback: () -> Unit = {}) {
            CoroutineScope(Dispatchers.IO).launch {
                GlobalData.dataStore?.edit {
                    it[key] = data
                }
                callback()
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
                dataFlow?.collect {
                    if (it != null) {
                        callback(it)
                    } else {
                        onNull()
                    }
                    cancel()
                }
            }
        }
    }
}