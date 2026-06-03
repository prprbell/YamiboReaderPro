package org.shirakawatyu.yamibo.novel.util.reader

import android.util.Log
import androidx.collection.LruCache

import com.alibaba.fastjson2.annotation.JSONCreator
import com.alibaba.fastjson2.annotation.JSONField

/**
 * 内存缓存数据类，用于存储页面缓存信息。
 * @property cachedPageNum 已缓存的页码
 * @property htmlContent 页面HTML内容
 * @property maxPageNum 最大页码
 * @property authorId 作者ID
 */
data class CacheData @JSONCreator constructor(
    @JSONField(name = "cachedPageNum") val cachedPageNum: Int = 0,
    @JSONField(name = "htmlContent") val htmlContent: String = "",
    @JSONField(name = "maxPageNum") val maxPageNum: Int = 1,
    @JSONField(name = "authorId") val authorId: String? = null
)

/**
 * 内存缓存管理工具
 * 负责在内存中存储和检索页面缓存，并验证HTML内容有效性
 */
class CacheUtil {
    companion object {
        private const val logTag = "CacheUtil"

        private const val CACHE_SIZE_IN_MIB = 64
        private val cacheSizeInBytes = CACHE_SIZE_IN_MIB * 1024 * 1024

        /**
         * LruCache实现
         * Key: String 唯一键
         * Value: CacheData 缓存的数据
         */
        private val inMemoryCache: LruCache<String, CacheData> =
            object : LruCache<String, CacheData>(cacheSizeInBytes) {

                override fun sizeOf(key: String, value: CacheData): Int {
                    val htmlSize = value.htmlContent.length * 2
                    val keySize = key.length * 2
                    val totalSize = htmlSize + keySize + 256
                    return totalSize
                }
            }

        /**
         * 内部辅助函数，用于根据小说URL和页码生成一个唯一的Key
         */
        private fun generateKey(novelUrl: String, pageNum: Int): String {
            return "$novelUrl::$pageNum"
        }

        /**
         * 验证HTML内容
         */
        private fun isHtmlContentValid(htmlContent: String): Boolean {
            // 检查 1: 是否为空白
            if (htmlContent.isBlank()) {
                Log.w(logTag, "Validation FAILED: HTML content is blank.")
                return false
            }

            // 检查 2: 是否过短 (例如，"<html></html>" 长度很短)
            if (htmlContent.length < 300) {
                Log.w(
                    logTag,
                    "Validation FAILED: HTML content is too short (${htmlContent.length} chars)."
                )
                return false
            }

            // 检查 3: 是否包含已知的错误标识
            if (htmlContent.contains("[Error] Content element not found")) {
                Log.w(logTag, "Validation FAILED: HTML content contains known error string.")
                return false
            }
            // 检查 4: 是否包含特定的HTML标记
            if (!htmlContent.contains("class=\"message\"")) {
                Log.w(logTag, "Validation FAILED: HTML content missing 'message' class.")
                return false
            }

            return true
        }

        /**
         * 获取指定小说和指定页码的缓存
         */
        fun getCache(novelUrl: String, pageNum: Int, callback: (data: CacheData?) -> Unit) {
            val key = generateKey(novelUrl, pageNum)
            val data = inMemoryCache[key]
            callback(data)
        }

        /**
         * 保存缓存数据
         */
        fun saveCache(novelUrl: String, data: CacheData) {
            val key = generateKey(novelUrl, data.cachedPageNum)

            if (!isHtmlContentValid(data.htmlContent)) {
                Log.w(logTag, "Validation FAILED. Not caching invalid content for key: $key")
                inMemoryCache.remove(key)
                return
            }
            inMemoryCache.put(key, data)
        }

        /**
         * 手动清除特定内存缓存的条目
         */
        fun clearCacheEntry(novelUrl: String, pageNum: Int) {
            val key = generateKey(novelUrl, pageNum)
            Log.w(logTag, "Manually clearing in-memory cache for key: $key")
            inMemoryCache.remove(key)
        }

        /**
         * 根据主 key + 兼容 key 读取内存缓存。
         *
         * 主 key 命中：顺手清理同页旧 key。
         * 旧 key 命中：迁移到主 key，然后删除旧 key。
         */
        fun getCacheCompat(
            primaryUrl: String,
            pageNum: Int,
            aliasUrls: List<String> = emptyList(),
            callback: (data: CacheData?) -> Unit
        ) {
            val keys = buildList {
                add(primaryUrl)
                aliasUrls.forEach { alias ->
                    if (alias.isNotBlank()) add(alias)
                }
            }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val primaryKey = generateKey(primaryUrl, pageNum)

            // 1. 主 key 优先
            inMemoryCache[primaryKey]?.let { data ->
                // 主 key 已经存在时，清掉同页旧 key，避免内存里继续保留双份身份。
                keys.drop(1).forEach { alias ->
                    inMemoryCache.remove(generateKey(alias, pageNum))
                }
                callback(data)
                return
            }

            // 2. fallback 旧 key；命中后迁移并删除旧 key
            for (keyUrl in keys.drop(1)) {
                val oldKey = generateKey(keyUrl, pageNum)
                val data = inMemoryCache[oldKey]
                if (data != null) {
                    saveCache(primaryUrl, data)
                    inMemoryCache.remove(oldKey)
                    callback(data)
                    return
                }
            }

            callback(null)
        }

        /**
         * 只读取主 key + 兼容 key 下的内存缓存，不迁移、不删除。
         * 用于后台预热前的"是否已有缓存"判断，避免为了判断而产生副作用。
         */
        fun peekCacheCompat(
            primaryUrl: String,
            pageNum: Int,
            aliasUrls: List<String> = emptyList()
        ): CacheData? {
            val keys = buildList {
                add(primaryUrl)
                aliasUrls.forEach { alias ->
                    if (alias.isNotBlank()) add(alias)
                }
            }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            for (keyUrl in keys) {
                inMemoryCache[generateKey(keyUrl, pageNum)]?.let { return it }
            }
            return null
        }

        /**
         * 清理主 key 和旧 key 下的同一页内存缓存。
         */
        fun clearCacheEntryCompat(
            primaryUrl: String,
            pageNum: Int,
            aliasUrls: List<String> = emptyList()
        ) {
            val keys = buildList {
                add(primaryUrl)
                aliasUrls.forEach { alias ->
                    if (alias.isNotBlank()) add(alias)
                }
            }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            keys.forEach { keyUrl ->
                clearCacheEntry(keyUrl, pageNum)
            }
        }

    }
}