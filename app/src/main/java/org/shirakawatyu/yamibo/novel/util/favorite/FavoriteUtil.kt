package org.shirakawatyu.yamibo.novel.util.favorite

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
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import kotlin.coroutines.resume

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
            val dataStore = GlobalData.Companion.dataStore
                ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data.map { preferences ->
                writeMutex.withLock {
                    pendingFavMap?.let {
                        return@withLock it.values.toList()
                    }

                    val jsonString = preferences[key]
                    if (jsonString != null) {
                        try {
                            jsonToHashMap(jsonString).values.toList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
            }
        }

        fun saveFavoriteOrder(orderedList: List<Favorite>) {
            ioScope.launch {
                writeMutex.withLock {
                    val favMap = LinkedHashMap<String, Favorite>()

                    for (fav in orderedList) {
                        favMap[fav.url] = fav
                    }

                    pendingFavMap = favMap

                    saveJob?.cancel()
                    saveJob = launch {
                        delay(1500L)
                        writeMutex.withLock {
                            pendingFavMap?.let {
                                DataStoreUtil.Companion.addData(JSON.toJSONString(it), key)
                                pendingFavMap = null
                            }
                        }
                    }
                }
            }
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
                        DataStoreUtil.Companion.addData(
                            JSON.toJSONString(oldMap),
                            key
                        ) { cont.resume(Unit) }
                    }
                }
            }
        }

        suspend fun mergeFavoritesProgressiveSuspend(pageList: List<Favorite>): Boolean {
            return writeMutex.withLock {
                val oldMap = getFavoriteMapSuspend()
                var hasNewItems = false
                var hasUpdatedFavIds = false
                val newMap = LinkedHashMap<String, Favorite>()

                val pureNewItems = mutableListOf<Favorite>()

                for (netFav in pageList) {
                    val oldFav = oldMap[netFav.url]
                    if (oldFav == null) {
                        pureNewItems.add(netFav)
                        hasNewItems = true
                    } else {
                        // 发现老数据：仅在内存中做 O(1) 的脏数据更新，把缺失的 favId 补上
                        if (oldFav.favId != netFav.favId && !netFav.favId.isNullOrEmpty()) {
                            oldFav.favId = netFav.favId
                            hasUpdatedFavIds = true
                        }
                    }
                }

                for (item in pureNewItems) {
                    newMap[item.url] = item
                }

                for ((url, oldFav) in oldMap) {
                    newMap[url] = oldFav
                }

                if (hasNewItems || hasUpdatedFavIds) {
                    pendingFavMap = null
                    suspendCancellableCoroutine { cont ->
                        DataStoreUtil.Companion.addData(
                            JSON.toJSONString(newMap),
                            key
                        ) { cont.resume(Unit) }
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
                    DataStoreUtil.Companion.addData(JSON.toJSONString(cleanedMap), key)
                }
            }
        }

        suspend fun updateFavoriteSuspend(favorite: Favorite) {
            writeMutex.withLock {
                val map = getFavoriteMapSuspend()
                if (map.containsKey(favorite.url)) {
                    map[favorite.url] = favorite
                    pendingFavMap = null
                    suspendCancellableCoroutine { cont ->
                        DataStoreUtil.Companion.addData(JSON.toJSONString(map), key) {
                            cont.resume(
                                Unit
                            )
                        }
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
                        suspendCancellableCoroutine { cont ->
                            DataStoreUtil.Companion.addData(
                                JSON.toJSONString(map),
                                key
                            ) { cont.resume(Unit) }
                        }
                    }
                }
            }
        }

        private fun jsonToHashMap(text: String): LinkedHashMap<String, Favorite> {
            val map = LinkedHashMap<String, Favorite>()
            try {
                val jsonObject: JSONObject = JSON.parseObject(text)
                jsonObject.values.forEach {
                    val obj = it as JSONObject
                    val fav = Favorite(
                        title = obj.getString("title") ?: "",
                        url = obj.getString("url") ?: "",
                        lastPage = obj.getIntValue("lastPage"),
                        lastView = obj.getIntValue("lastView"),
                        lastChapter = obj.getString("lastChapter"),
                        authorId = obj.getString("authorId"),
                        isHidden = obj.getBooleanValue("isHidden"),
                        type = obj.getIntValue("type"),
                        lastMangaUrl = obj.getString("lastMangaUrl"),
                        favId = obj.getString("favId")
                    )
                    map[fav.url] = fav
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return map
        }

        suspend fun getFavoriteMapSuspend(): LinkedHashMap<String, Favorite> =
            suspendCancellableCoroutine { cont ->
                pendingFavMap?.let {
                    cont.resume(LinkedHashMap(it))
                    return@suspendCancellableCoroutine
                }

                DataStoreUtil.Companion.getData(key, callback = {
                    try {
                        val favMap = jsonToHashMap(it)
                        cont.resume(favMap)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        cont.resume(LinkedHashMap())
                    }
                }, onNull = {
                    cont.resume(LinkedHashMap())
                })
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
                    suspendCancellableCoroutine { cont ->
                        DataStoreUtil.Companion.addData(
                            JSON.toJSONString(newMap),
                            key
                        ) { cont.resume(Unit) }
                    }
                }
            }
        }
    }
}