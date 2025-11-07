package org.shirakawatyu.yamibo.novel.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import org.shirakawatyu.yamibo.novel.bean.Favorite

class FavoriteUtil {
    companion object {
        private val key = stringPreferencesKey("yamibo_favorite")

        /**
         * 合并网络收藏和本地收藏。
         * 1. 将网络上新增的收藏项放在顶部。
         * 2. 保留本地已有的收藏项的顺序（包括用户手动排序后的顺序）。
         * 3. 移除在网络上已被删除的收藏项。
         */
        fun addFavorite(favorites: List<Favorite>, callback: (list: List<Favorite>) -> Unit) {
            // 1. 获取本地存储的旧收藏 Map (保持了顺序)
            getFavoriteMap { oldMap ->

                val networkMap = favorites.associateBy { it.url }
                val finalOrderedMap = LinkedHashMap<String, Favorite>()

                // 2. 添加新项目：遍历网络列表，如果项目不在旧Map中，则它是新项目
                for (netFav in favorites) {
                    if (!oldMap.containsKey(netFav.url)) {
                        finalOrderedMap[netFav.url] = netFav
                    }
                }

                // 3. 添加旧项目：遍历本地的旧Map (保持用户之前的顺序)
                for (oldEntry in oldMap.entries) {
                    val url = oldEntry.key
                    val oldFavData = oldEntry.value

                    // 检查这个旧项目是否仍然存在于网络列表中
                    if (networkMap.containsKey(url)) {
                        // 它还存在。获取网络版本（用于更新标题等）
                        val netFavData = networkMap[url]!!

                        // 创建更新后的 Favorite，
                        // 保留本地的阅读进度 (lastPage, lastChapter等)，
                        // 但更新网络上的信息 (如 title)
                        val updatedFav = oldFavData.copy(
                            title = netFavData.title
                            // 注意：这里保留了 oldFavData 的 lastPage, lastView, lastChapter, authorId
                        )
                        finalOrderedMap[url] = updatedFav
                    }
                    // 如果 oldEntry 不在 networkMap 中，它将被自动跳过
                }

                // 4. 保存这个合并后的、新顺序的 Map
                DataStoreUtil.addData(JSON.toJSONString(finalOrderedMap), key)

                // 5. 返回新列表给 VM
                callback(finalOrderedMap.values.toList())
            }
        }

        /**
         * 保存用户手动排序后的列表。
         */
        fun saveFavoriteOrder(orderedList: List<Favorite>) {
            val favMap = LinkedHashMap<String, Favorite>()
            // 按照列表的新顺序重新构建 LinkedHashMap
            orderedList.forEach { fav ->
                favMap[fav.url] = fav
            }
            // addData 会在 IO 协程中执行
            DataStoreUtil.addData(JSON.toJSONString(favMap), key)
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
            }, onNull = {
                // [已修改] 确保 onNull 时返回一个空的 LinkedHashMap
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
                    obj["authorId"] as? String
                )
                map[fav.url] = fav
            }
            return map
        }
    }
}