package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.GlobalData

class FavoriteUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_favorite")

        /**
         * 提供一个 Flow，用于实时监听收藏列表的变化。
         */
        fun getFavoriteFlow(): Flow<List<Favorite>> {
            val dataStore =
                GlobalData.dataStore ?: throw IllegalStateException("DataStore not initialized")
            return dataStore.data
                .map { preferences ->
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

        /**
         * (不是给论坛账号添加新的收藏）合并网络收藏和本地收藏。
         * 1. 将网络上新增的收藏项放在顶部。
         * 2. 保留本地已有的收藏项的顺序（包括用户手动排序后的顺序）。
         * 3. 移除在网络上已被删除的收藏项。
         */
        fun addFavorite(favorites: List<Favorite>, callback: (list: List<Favorite>) -> Unit) {
            getFavoriteMap { oldMap ->

                val networkMap = favorites.associateBy { it.url }
                val finalOrderedMap = LinkedHashMap<String, Favorite>()

                for (netFav in favorites) {
                    if (!oldMap.containsKey(netFav.url)) {
                        finalOrderedMap[netFav.url] = netFav
                    }
                }

                for (oldEntry in oldMap.entries) {
                    val url = oldEntry.key
                    val oldFavData = oldEntry.value

                    if (networkMap.containsKey(url)) {
                        finalOrderedMap[url] = oldFavData
                    }
                }

                DataStoreUtil.addData(JSON.toJSONString(finalOrderedMap), key)

                callback(finalOrderedMap.values.toList())
            }
        }

        fun checkAndUpdateTitle(url: String, rawTitle: String?) {
            if (rawTitle.isNullOrBlank()) return
            val cleanTitle = rawTitle.replace(Regex(" - .*"), "")

            getFavoriteMap { map ->
                map[url]?.let { fav ->
                    if (fav.title != cleanTitle) {
                        updateFavorite(fav.copy(title = cleanTitle))
                    }
                }
            }
        }

        /**
         * 保存用户手动排序后的列表。
         */
        fun saveFavoriteOrder(orderedList: List<Favorite>) {
            val favMap = LinkedHashMap<String, Favorite>()
            orderedList.forEach { fav ->
                favMap[fav.url] = fav
            }
            DataStoreUtil.addData(JSON.toJSONString(favMap), key)
        }


        fun updateFavorite(favorite: Favorite) {
            DataStoreUtil.getData(key, callback = {
                val favMap = jsonToHashMap(it)
                favMap[favorite.url] = favorite
                DataStoreUtil.addData(JSON.toJSONString(favMap), key)
            })
        }

        fun updateHiddenStatus(urls: Set<String>, isHidden: Boolean, onComplete: () -> Unit) {
            DataStoreUtil.getData(key, callback = { data ->
                val favMap = jsonToHashMap(data)
                var changed = false
                urls.forEach { url ->
                    favMap[url]?.let { fav ->
                        if (fav.isHidden != isHidden) {
                            fav.isHidden = isHidden
                            changed = true
                        }
                    }
                }
                if (changed) {
                    DataStoreUtil.addData(JSON.toJSONString(favMap), key, onComplete)
                } else {
                    onComplete()
                }
            }, onNull = onComplete)
        }

        fun getFavoriteMap(callback: (map: Map<String, Favorite>) -> Unit) {
            DataStoreUtil.getData(key, callback = {
                val favMap = jsonToHashMap(it)
                callback(favMap)
            }, onNull = {
                callback(LinkedHashMap())
            })
        }

        fun getFavorite(callback: (list: List<Favorite>) -> Unit) {
            getFavoriteMap {
                callback(it.values.toList())
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
                    obj["isHidden"] as? Boolean ?: false
                )
                map[fav.url] = fav
            }
            return map
        }
    }
}