package org.shirakawatyu.yamibo.novel.util.updateCheck

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.OtherUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import kotlin.coroutines.resume

class OtherUpdateCheckUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_update_check_other")
        private val writeMutex = Mutex()

        fun getUpdateCheckFlow(): Flow<List<OtherUpdateCheckProfile>> {
            val dataStore = GlobalData.dataStore
                ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data.map { preferences ->
                val jsonString = preferences[key]
                if (jsonString != null) {
                    try {
                        jsonToList(jsonString)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }

        suspend fun saveProfileSuspend(profile: OtherUpdateCheckProfile) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[profile.url] = map[profile.url]?.let { old ->
                    profile.copy(
                        autoCheckEnabled = old.autoCheckEnabled,
                        autoCheckIntervalHours = old.autoCheckIntervalHours
                    )
                } ?: profile
                saveMapSuspend(map)
            }
        }

        suspend fun removeProfileSuspend(url: String) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map.remove(url)
                saveMapSuspend(map)
            }
        }

        suspend fun updateRepliesSuspend(
            url: String,
            newReplies: Int,
            hasUpdate: Boolean,
            lastCheckTime: Long? = null
        ) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[url]?.let {
                    map[url] = it.copy(
                        savedReplies = newReplies,
                        hasUpdate = hasUpdate,
                        lastCheckTime = lastCheckTime ?: it.lastCheckTime
                    )
                    saveMapSuspend(map)
                }
            }
        }

        suspend fun updateCheckTimeSuspend(url: String, lastCheckTime: Long) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[url]?.let {
                    map[url] = it.copy(lastCheckTime = lastCheckTime)
                    saveMapSuspend(map)
                }
            }
        }

        suspend fun updateAutoCheckSuspend(
            url: String,
            enabled: Boolean,
            intervalHours: Int
        ) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[url]?.let {
                    map[url] = it.copy(
                        autoCheckEnabled = enabled,
                        autoCheckIntervalHours = intervalHours
                    )
                    saveMapSuspend(map)
                }
            }
        }

        suspend fun clearUpdateFlagSuspend(url: String) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[url]?.let {
                    if (it.hasUpdate) {
                        map[url] = it.copy(hasUpdate = false)
                        saveMapSuspend(map)
                    }
                }
            }
        }

        suspend fun getMapSuspend(): LinkedHashMap<String, OtherUpdateCheckProfile> =
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.getData(key, callback = {
                    try {
                        cont.resume(jsonToMap(it))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        cont.resume(LinkedHashMap())
                    }
                }, onNull = {
                    cont.resume(LinkedHashMap())
                })
            }

        private suspend fun saveMapSuspend(map: LinkedHashMap<String, OtherUpdateCheckProfile>) {
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.addData(JSON.toJSONString(map), key) { cont.resume(Unit) }
            }
        }

        private fun jsonToMap(text: String): LinkedHashMap<String, OtherUpdateCheckProfile> {
            val map = LinkedHashMap<String, OtherUpdateCheckProfile>()
            val jsonObject: JSONObject = JSON.parseObject(text)
            jsonObject.values.forEach {
                val obj = it as JSONObject
                val profile = OtherUpdateCheckProfile(
                    title = obj.getString("title") ?: "",
                    url = obj.getString("url") ?: "",
                    savedReplies = obj.getIntValue("savedReplies"),
                    hasUpdate = obj.getBooleanValue("hasUpdate"),
                    lastCheckTime = obj.getLongValue("lastCheckTime"),
                    autoCheckEnabled = obj.getBooleanValue("autoCheckEnabled"),
                    autoCheckIntervalHours = obj.getIntValue("autoCheckIntervalHours")
                        .takeIf { it > 0 } ?: 12
                )
                map[profile.url] = profile
            }
            return map
        }

        private fun jsonToList(text: String): List<OtherUpdateCheckProfile> =
            jsonToMap(text).values.toList()
    }
}
