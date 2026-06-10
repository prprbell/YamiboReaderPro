package org.shirakawatyu.yamibo.novel.util.updateCheck

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.NovelUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import kotlin.coroutines.resume

class NovelUpdateCheckUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_update_check_novel")
        private val legacySpecialFollowKey = stringPreferencesKey("yamibo_special_follow_novel")
        private val writeMutex = Mutex()

        fun getUpdateCheckFlow(): Flow<List<NovelUpdateCheckProfile>> {
            val dataStore = GlobalData.dataStore
                ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data.map { preferences ->
                val jsonString = preferences[key] ?: preferences[legacySpecialFollowKey]
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

        suspend fun saveProfileSuspend(profile: NovelUpdateCheckProfile) {
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

        suspend fun getMapSuspend(): LinkedHashMap<String, NovelUpdateCheckProfile> =
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.getData(key, callback = {
                    try {
                        cont.resume(jsonToMap(it))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        cont.resume(LinkedHashMap())
                    }
                }, onNull = {
                    DataStoreUtil.getData(legacySpecialFollowKey, callback = {
                        try {
                            cont.resume(jsonToMap(it))
                        } catch (e: Exception) {
                            e.printStackTrace()
                            cont.resume(LinkedHashMap())
                        }
                    }, onNull = {
                        cont.resume(LinkedHashMap())
                    })
                })
            }

        private suspend fun saveMapSuspend(map: LinkedHashMap<String, NovelUpdateCheckProfile>) {
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.addData(JSON.toJSONString(map), key) { cont.resume(Unit) }
            }
        }

        private fun jsonToMap(text: String): LinkedHashMap<String, NovelUpdateCheckProfile> {
            val map = LinkedHashMap<String, NovelUpdateCheckProfile>()
            val jsonObject: JSONObject = JSON.parseObject(text)
            jsonObject.values.forEach {
                val obj = it as JSONObject
                val profile = NovelUpdateCheckProfile(
                    title = obj.getString("title") ?: "",
                    url = obj.getString("url") ?: "",
                    authorId = obj.getString("authorId") ?: "",
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

        private fun jsonToList(text: String): List<NovelUpdateCheckProfile> =
            jsonToMap(text).values.toList()
    }
}
