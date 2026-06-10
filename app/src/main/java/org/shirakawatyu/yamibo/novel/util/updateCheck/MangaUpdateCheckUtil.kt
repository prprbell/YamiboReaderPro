package org.shirakawatyu.yamibo.novel.util.updateCheck

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckStrategy
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import kotlin.coroutines.resume

class MangaUpdateCheckUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_update_check_manga")
        private val legacySpecialFollowKey = stringPreferencesKey("yamibo_special_follow_manga")
        private val writeMutex = Mutex()

        fun getUpdateCheckFlow(): Flow<List<MangaUpdateCheckProfile>> {
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

        suspend fun saveProfileSuspend(profile: MangaUpdateCheckProfile) {
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

        suspend fun updateSnapshotSuspend(
            url: String,
            chapterCount: Int,
            latestTid: String,
            hasUpdate: Boolean,
            lastCheckTime: Long? = null,
            searchKeyword: String? = null,
            strategy: MangaUpdateCheckStrategy? = null,
            cleanBookName: String? = null
        ) {
            writeMutex.withLock {
                val map = getMapSuspend()
                map[url]?.let {
                    map[url] = it.copy(
                        savedChapterCount = chapterCount,
                        savedLatestTid = latestTid,
                        hasUpdate = hasUpdate,
                        lastCheckTime = lastCheckTime ?: it.lastCheckTime,
                        searchKeyword = searchKeyword ?: it.searchKeyword,
                        strategy = strategy ?: it.strategy,
                        cleanBookName = cleanBookName ?: it.cleanBookName
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

        suspend fun getMapSuspend(): LinkedHashMap<String, MangaUpdateCheckProfile> =
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

        private suspend fun saveMapSuspend(map: LinkedHashMap<String, MangaUpdateCheckProfile>) {
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.addData(JSON.toJSONString(map), key) { cont.resume(Unit) }
            }
        }

        private fun jsonToMap(text: String): LinkedHashMap<String, MangaUpdateCheckProfile> {
            val map = LinkedHashMap<String, MangaUpdateCheckProfile>()
            val jsonObject: JSONObject = JSON.parseObject(text)
            jsonObject.values.forEach {
                val obj = it as JSONObject
                val strategy = try {
                    MangaUpdateCheckStrategy.valueOf(obj.getString("strategy") ?: "SEARCH")
                } catch (_: Exception) {
                    MangaUpdateCheckStrategy.SEARCH
                }
                val profile = MangaUpdateCheckProfile(
                    title = obj.getString("title") ?: "",
                    url = obj.getString("url") ?: "",
                    cleanBookName = obj.getString("cleanBookName") ?: "",
                    searchKeyword = obj.getString("searchKeyword"),
                    strategy = strategy,
                    savedChapterCount = obj.getIntValue("savedChapterCount"),
                    savedLatestTid = obj.getString("savedLatestTid") ?: "",
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

        private fun jsonToList(text: String): List<MangaUpdateCheckProfile> =
            jsonToMap(text).values.toList()
    }
}
