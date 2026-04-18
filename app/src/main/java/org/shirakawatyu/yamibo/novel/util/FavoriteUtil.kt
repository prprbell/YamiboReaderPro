package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.GlobalData
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 收藏管理工具
 * 采用内存防抖(Debounce)策略，化解高频 JSON 序列化带来的 I/O 抖动
 */
class FavoriteUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_favorite")

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var saveJob: Job? = null
        private val writeMutex = Mutex()
        private var pendingFavMap: LinkedHashMap<String, Favorite>? = null
        fun getFavoriteFlow(): Flow<List<Favorite>> {
            val dataStore = GlobalData.dataStore ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data.map { preferences ->
                val jsonString = preferences[key]
                if (jsonString != null) {
                    try {
                        jsonToHashMap(jsonString).values.toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }

        // 修改后
        fun saveFavoriteOrder(orderedList: List<Favorite>) {
            ioScope.launch {
                writeMutex.withLock {
                    val currentMap = pendingFavMap ?: getFavoriteMapSuspend()
                    val favMap = LinkedHashMap<String, Favorite>()

                    for (fav in orderedList) {
                        currentMap[fav.url]?.let { favMap[fav.url] = it }
                    }
                    for ((url, fav) in currentMap) {
                        if (!favMap.containsKey(url)) {
                            favMap[url] = fav
                        }
                    }

                    pendingFavMap = favMap

                    saveJob?.cancel()
                    saveJob = launch {
                        delay(1500L)
                        writeMutex.withLock {
                            pendingFavMap?.let {
                                DataStoreUtil.addData(JSON.toJSONString(it), key)
                                pendingFavMap = null // 写入完毕后清空
                            }
                        }
                    }
                }
            }
        }

        suspend fun getFavoriteMapSuspend(): LinkedHashMap<String, Favorite> =
            suspendCancellableCoroutine { cont ->
                pendingFavMap?.let {
                    cont.resume(LinkedHashMap(it))
                    return@suspendCancellableCoroutine
                }

                DataStoreUtil.getData(key, callback = {
                    val favMap = jsonToHashMap(it)
                    cont.resume(favMap)
                }, onNull = {
                    cont.resume(LinkedHashMap())
                })
            }

        suspend fun updateHiddenStatus(urls: Set<String>, isHidden: Boolean) {
            writeMutex.withLock {
                val oldMap = getFavoriteMapSuspend()
                var changed = false
                urls.forEach { url ->
                    oldMap[url]?.let { fav ->
                        if (fav.isHidden != isHidden) {
                            fav.isHidden = isHidden
                            changed = true
                        }
                    }
                }
                if (changed) {
                    pendingFavMap = null
                    suspendCancellableCoroutine { cont ->
                        DataStoreUtil.addData(JSON.toJSONString(oldMap), key) { cont.resume(Unit) }
                    }
                }
            }
        }

        suspend fun mergeFavoritesProgressiveSuspend(pageList: List<Favorite>): Boolean {
            return writeMutex.withLock {
                val oldMap = getFavoriteMapSuspend()
                var hasNewItems = false
                val newMap = LinkedHashMap<String, Favorite>()

                for (netFav in pageList) {
                    if (!oldMap.containsKey(netFav.url)) {
                        newMap[netFav.url] = netFav
                        hasNewItems = true
                    }
                }

                for ((url, oldFav) in oldMap) {
                    newMap[url] = oldFav
                }

                if (hasNewItems) {
                    pendingFavMap = null
                    suspendCancellableCoroutine<Unit> { cont ->
                        DataStoreUtil.addData(JSON.toJSONString(newMap), key) { cont.resume(Unit) }
                    }
                }
                hasNewItems
            }
        }

        suspend fun cleanupDeletedFavoritesSuspend(fullNetworkList: List<Favorite>) {
            writeMutex.withLock {
                val oldMap = getFavoriteMapSuspend()
                val networkUrls = fullNetworkList.map { it.url }.toSet()
                val cleanedMap = LinkedHashMap<String, Favorite>()

                oldMap.forEach { (url, fav) ->
                    if (networkUrls.contains(url)) {
                        cleanedMap[url] = fav
                    }
                }

                if (cleanedMap.size != oldMap.size) {
                    pendingFavMap = null
                    DataStoreUtil.addData(JSON.toJSONString(cleanedMap), key)
                }
            }
        }
        suspend fun updateFavoriteSuspend(favorite: Favorite) {
            writeMutex.withLock {
                val map = getFavoriteMapSuspend()
                if (map.containsKey(favorite.url)) {
                    map[favorite.url] = favorite
                    pendingFavMap = null
                    suspendCancellableCoroutine<Unit> { cont ->
                        DataStoreUtil.addData(JSON.toJSONString(map), key) { cont.resume(Unit) }
                    }
                }
            }
        }

        suspend fun checkAndUpdateTitleSuspend(url: String, title: String?) {
            if (title.isNullOrBlank()) return
            writeMutex.withLock {
                val map = getFavoriteMapSuspend()
                map[url]?.let { fav ->
                    if (fav.title != title) {
                        map[url] = fav.copy(title = title)
                        pendingFavMap = null
                        suspendCancellableCoroutine<Unit> { cont ->
                            DataStoreUtil.addData(JSON.toJSONString(map), key) { cont.resume(Unit) }
                        }
                    }
                }
            }
        }
        private fun jsonToHashMap(text: String): LinkedHashMap<String, Favorite> {
            val jsonObject: JSONObject = JSON.parseObject(text)
            val map = LinkedHashMap<String, Favorite>()
            jsonObject.values.forEach {
                val obj = it as JSONObject
                val fav = Favorite(
                    obj["title"] as String,
                    obj["url"] as String,
                    obj["lastPage"] as Int,
                    obj["lastView"] as Int,
                    obj["lastChapter"] as? String,
                    obj["authorId"] as? String,
                    obj["isHidden"] as? Boolean ?: false,
                    obj["type"] as? Int ?: 0,
                    obj["lastMangaUrl"] as? String
                )
                map[fav.url] = fav
            }
            return map
        }
        suspend fun moveUrlToTopSuspend(url: String) {
            writeMutex.withLock {
                val map = getFavoriteMapSuspend()
                if (map.containsKey(url)) {
                    val fav = map.remove(url)!!
                    val newMap = LinkedHashMap<String, Favorite>()
                    newMap[url] = fav
                    newMap.putAll(map)
                    pendingFavMap = null
                    suspendCancellableCoroutine<Unit> { cont ->
                        DataStoreUtil.addData(JSON.toJSONString(newMap), key) { cont.resume(Unit) }
                    }
                }
            }
        }
    }
}