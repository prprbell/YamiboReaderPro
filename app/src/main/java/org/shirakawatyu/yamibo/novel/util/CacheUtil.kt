package org.shirakawatyu.yamibo.novel.util

import android.util.Log
import androidx.collection.LruCache

data class CacheData(
    val cachedPageNum: Int = 0,
    val htmlContent: String = "",
    val maxPageNum: Int = 1,
    val authorId: String? = null
)

// 缓存管理类
class CacheUtil {
    companion object {
        private const val logTag = "CacheUtil"

        // 定义缓存的总大小
        // 根据需要调整（20mb）
        private const val CACHE_SIZE_IN_MIB = 20
        private val cacheSizeInBytes = CACHE_SIZE_IN_MIB * 1024 * 1024

        /**
         * LruCache 的实现
         * Key: String - 唯一键 (例如 "novel/url::page/2")
         * Value: CacheData - 缓存的数据
         */
        private val inMemoryCache: LruCache<String, CacheData> =
            object : LruCache<String, CacheData>(cacheSizeInBytes) {

                /**
                 * LruCache 的核心。
                 * 告诉 LruCache 存入的每一项 (CacheData) 占用了多少内存。
                 */
                override fun sizeOf(key: String, value: CacheData): Int {
                    // 估算内存占用 (字节)
                    // 字符串在Java/Kotlin中每个字符占2字节 (UTF-16)
                    val htmlSize = value.htmlContent.length * 2
                    val keySize = key.length * 2
                    // 加上其他字段和对象开销的粗略估算 (例如 256 字节)
                    val totalSize = htmlSize + keySize + 256

                    Log.v(logTag, "Cache item size: ${totalSize / 1024} KB for key: $key")
                    return totalSize
                }

                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    oldValue: CacheData,
                    newValue: CacheData?
                ) {
                    if (evicted) {
                        Log.i(logTag, "Cache evicted (purged) item: $key")
                    }
                }
            }

        /**
         * 内部辅助函数，用于根据小说URL和页码生成一个唯一的Key
         */
        private fun generateKey(novelUrl: String, pageNum: Int): String {
            return "$novelUrl::$pageNum"
        }

        /**
         * 获取指定小说和指定页码的缓存
         */
        fun getCache(novelUrl: String, pageNum: Int, callback: (data: CacheData?) -> Unit) {
            val key = generateKey(novelUrl, pageNum)
            val data = inMemoryCache[key]
            if (data != null) {
                Log.i(logTag, "Cache HIT for key: $key")
            } else {
                Log.i(logTag, "Cache MISS for key: $key")
            }
            callback(data)
        }

        /**
         * 保存缓存数据
         */
        fun saveCache(novelUrl: String, data: CacheData) {
            val key = generateKey(novelUrl, data.cachedPageNum)
            Log.i(logTag, "Caching item: $key")
            inMemoryCache.put(key, data)
        }

        /**
         * 清除特定页面的缓存 (如果是最后一页，不缓存)
         */
        fun clearCacheForPage(novelUrl: String, pageNum: Int) {
            val key = generateKey(novelUrl, pageNum)
            Log.i(logTag, "Clearing cache for page: $key")
            inMemoryCache.remove(key)
        }
    }
}