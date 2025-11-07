package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import org.shirakawatyu.yamibo.novel.bean.Favorite

class FavoriteUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_favorite")

        fun addFavorite(favorites: List<Favorite>, callback: (list: List<Favorite>) -> Unit) {
            var favMap = LinkedHashMap<String, Favorite>()
            DataStoreUtil.getData(key, callback = {
                favMap = jsonToHashMap(it)
                favorites.forEach { favorite ->
                    if (!favMap.contains(favorite.url)) {
                        favMap[favorite.url] = favorite
                    }
                }
                val netMap = favorites.associateBy { it1 -> it1.url }
                val favKeys = ArrayList<String>()
                favKeys.addAll(favMap.keys)
                favKeys.forEach { k ->
                    if (!netMap.contains(k)) {
                        favMap.remove(k)
                    }
                }
                DataStoreUtil.addData(JSON.toJSONString(favMap), key)
                callback(favMap.values.toList())
            }, onNull = {
                favorites.forEach { favorite ->
                    favMap[favorite.url] = favorite
                }
                DataStoreUtil.addData(JSON.toJSONString(favMap), key)
                callback(favMap.values.toList())
            })
        }

        fun updateFavorite(favorite: Favorite) {
            DataStoreUtil.getData(key, callback = {
                val favMap = jsonToHashMap(it)
                favMap[favorite.url] = favorite
                DataStoreUtil.addData(JSON.toJSONString(favMap), key)
            })
        }

        fun getFavoriteMap(callback: (map: Map<String, Favorite>) -> Unit) {
            DataStoreUtil.getData(key, callback = {
                val favMap = jsonToHashMap(it)
                callback(favMap)
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
                    obj["lastView"] as Int
                )
                map[fav.url] = fav
            }
            return map
        }
    }
}