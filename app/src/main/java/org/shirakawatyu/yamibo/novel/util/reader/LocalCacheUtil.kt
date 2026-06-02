package org.shirakawatyu.yamibo.novel.util.reader

import android.content.Context
import android.util.Log
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.TypeReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.collections.iterator

/**
 * 本地持久化缓存管理
 * 负责将小说页面缓存到应用私有目录，并提供索引管理
 */
class LocalCacheUtil(private val context: Context) {

    companion object {
        private const val LOG_TAG = "LocalCacheUtil"
        private const val CACHE_DIR = "novel_cache"
        private const val INDEX_FILE = "cache_index.json"

        @Volatile
        private var instance: LocalCacheUtil? = null

        fun getInstance(context: Context): LocalCacheUtil {
            return instance ?: synchronized(this) {
                instance ?: LocalCacheUtil(context.applicationContext).also { instance = it }
            }
        }
    }

    // 内存中的"事实来源"，用于缓存索引
    private val _index = MutableStateFlow<Map<String, CacheIndex>>(emptyMap())
    val index = _index.asStateFlow()

    // 用于后台磁盘I/O的协程作用域
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // App启动时，从磁盘加载一次索引
    init {
        ioScope.launch {
            val loadedIndex = readIndexFromDisk()
            // 修复fileSize为0的情况
            val fixedIndex = fixFileSizesIfNeeded(loadedIndex)
            _index.value = fixedIndex
        }
    }

    // 缓存索引数据类
    data class CacheIndex(
        val title: String? = null,
        val pages: MutableMap<Int, CachePageInfo> = mutableMapOf()
    )

    data class CachePageInfo(
        val pageNum: Int,
        val hasImages: Boolean,
        val timestamp: Long,
        val fileSize: Long
    )

    // 获取缓存根目录
    private fun getCacheDir(): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // 获取索引文件
    private fun getIndexFile(): File {
        return File(getCacheDir(), INDEX_FILE)
    }

    // 从磁盘读取索引
    private suspend fun readIndexFromDisk(): Map<String, CacheIndex> = withContext(Dispatchers.IO) {
        try {
            val indexFile = getIndexFile()
            if (!indexFile.exists()) {
                return@withContext emptyMap()
            }
            val jsonStr = indexFile.readText()
            val map = JSON.parseObject(
                jsonStr,
                object : TypeReference<Map<String, CacheIndex>>() {}
            )
            map ?: emptyMap()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "从磁盘读取缓存索引失败", e)
            emptyMap()
        }
    }

    // 修复索引中fileSize为0的情况
    private suspend fun fixFileSizesIfNeeded(
        loadedIndex: Map<String, CacheIndex>
    ): Map<String, CacheIndex> = withContext(Dispatchers.IO) {
        var needsUpdate = false
        val fixedIndex = loadedIndex.toMutableMap()

        for ((novelUrl, novelCache) in loadedIndex) {
            val fixedPages = novelCache.pages.toMutableMap()
            var pagesChanged = false

            for ((pageNum, pageInfo) in novelCache.pages) {
                // 如果fileSize为0，尝试从实际文件读取大小
                if (pageInfo.fileSize == 0L) {
                    val fileName = getCacheFileName(novelUrl, pageNum)
                    val cacheFile = File(getCacheDir(), fileName)

                    if (cacheFile.exists()) {
                        val actualSize = cacheFile.length()
                        if (actualSize > 0) {
                            fixedPages[pageNum] = pageInfo.copy(fileSize = actualSize)
                            pagesChanged = true
                            needsUpdate = true
                        }
                    }
                }
            }

            if (pagesChanged) {
                fixedIndex[novelUrl] = novelCache.copy(pages = fixedPages)
            }
        }

        // 如果有修复，保存回磁盘
        if (needsUpdate) {
            val indexFile = getIndexFile()
            val tempFile = File(indexFile.parent, "${indexFile.name}.tmp")
            try {
                val jsonStr = JSON.toJSONString(fixedIndex)
                tempFile.writeText(jsonStr)

                if (tempFile.exists()) {
                    if (indexFile.exists()) {
                        indexFile.delete()
                    }
                    tempFile.renameTo(indexFile)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "保存修复后的索引失败", e)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

        fixedIndex
    }

    // 写入索引：首先更新内存Flow，然后异步写入磁盘
    private fun writeIndex(newIndex: Map<String, CacheIndex>) {
        // 立即更新内存
        _index.value = newIndex

        // 启动一个后台任务将新索引写入磁盘
        ioScope.launch {
            val indexFile = getIndexFile()
            val tempFile = File(indexFile.parent, "${indexFile.name}.tmp")
            try {
                val jsonStr = JSON.toJSONString(newIndex)
                // 先写入临时文件
                tempFile.writeText(jsonStr)

                // 写入成功后，执行重命名替换
                if (tempFile.exists()) {
                    if (indexFile.exists()) {
                        indexFile.delete()
                    }
                    tempFile.renameTo(indexFile)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "将缓存索引写入磁盘失败", e)
            } finally {
                // 清理可能残留的临时文件
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    // 生成缓存文件名
    private fun getCacheFileName(novelUrl: String, pageNum: Int): String {
        val urlHash = novelUrl.hashCode().toString(16)
        return "cache_${urlHash}_${pageNum}.json"
    }

    // 保存页面
    suspend fun savePage(
        novelUrl: String,
        pageNum: Int,
        data: CacheData,
        hasImages: Boolean,
        title: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getCacheFileName(novelUrl, pageNum)
            val cacheFile = File(getCacheDir(), fileName)
            val jsonStr = JSON.toJSONString(data)

            // 计算准确的文件大小
            val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
            val fileSize = jsonBytes.size.toLong()
            // 写入缓存文件
            cacheFile.writeBytes(jsonBytes)

            val currentIndex = _index.value
            val newIndex = currentIndex.toMutableMap()
            val novelCache = newIndex.getOrPut(novelUrl) { CacheIndex() }

            val newPages = novelCache.pages.toMutableMap()
            newPages[pageNum] = CachePageInfo(
                pageNum = pageNum,
                hasImages = hasImages,
                timestamp = System.currentTimeMillis(),
                fileSize = fileSize
            )

            val normalizedTitle = title
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            newIndex[novelUrl] = novelCache.copy(
                title = normalizedTitle ?: novelCache.title,
                pages = newPages
            )

            writeIndex(newIndex)
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "保存缓存失败", e)
            false
        }
    }

    // 读取页面缓存
    suspend fun loadPage(novelUrl: String, pageNum: Int): CacheData? = withContext(Dispatchers.IO) {
        try {
            val fileName = getCacheFileName(novelUrl, pageNum)
            val cacheFile = File(getCacheDir(), fileName)

            if (!cacheFile.exists()) {
                return@withContext null
            }

            val jsonStr = cacheFile.readText()
            val data = JSON.parseObject(jsonStr, CacheData::class.java)
            data
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load cache", e)
            null
        }
    }

    // 检查页面是否已缓存
    suspend fun isPageCached(novelUrl: String, pageNum: Int): Boolean {
        return _index.value[novelUrl]?.pages?.containsKey(pageNum) ?: false
    }

    // 获取已缓存的页面列表
    suspend fun getCachedPages(novelUrl: String): List<CachePageInfo> {
        return _index.value[novelUrl]?.pages?.values?.sortedBy { it.pageNum } ?: emptyList()
    }

    // 删除单个页面
    suspend fun deletePage(novelUrl: String, pageNum: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getCacheFileName(novelUrl, pageNum)
            val cacheFile = File(getCacheDir(), fileName)

            if (cacheFile.exists()) {
                cacheFile.delete()
            }

            // 从内存更新
            val currentIndex = _index.value
            val newIndex = currentIndex.toMutableMap()
            val novelCache = newIndex[novelUrl]

            if (novelCache != null) {
                // 创建新map
                val newPages = novelCache.pages.toMutableMap()
                newPages.remove(pageNum)

                if (newPages.isEmpty()) {
                    newIndex.remove(novelUrl)
                } else {
                    newIndex[novelUrl] = novelCache.copy(pages = newPages)
                }

                writeIndex(newIndex)
            }

            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "删除缓存失败", e)
            false
        }
    }

    // 删除小说的所有缓存
    suspend fun deleteNovel(novelUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentIndex = _index.value
            val novelCache = currentIndex[novelUrl] ?: return@withContext true

            // 删除所有缓存文件
            novelCache.pages.keys.forEach { pageNum ->
                val fileName = getCacheFileName(novelUrl, pageNum)
                val cacheFile = File(getCacheDir(), fileName)
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
            }

            // 更新内存
            val newIndex = currentIndex.toMutableMap()
            newIndex.remove(novelUrl)
            writeIndex(newIndex)
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "删除小说缓存失败", e)
            false
        }
    }

    // 获取缓存统计信息
    suspend fun getCacheStats(novelUrl: String): CacheStats = withContext(Dispatchers.IO) {
        val pages = getCachedPages(novelUrl)
        val totalSize = pages.sumOf { it.fileSize }
        val pagesWithImages = pages.count { it.hasImages }

        CacheStats(
            totalPages = pages.size,
            totalSize = totalSize,
            pagesWithImages = pagesWithImages
        )
    }

    data class CacheStats(
        val totalPages: Int,
        val totalSize: Long,
        val pagesWithImages: Int
    )

    // 获取所有已缓存的小说URL列表
    suspend fun getAllCachedNovels(): List<String> {
        return _index.value.keys.toList()
    }

    // 清空所有缓存
    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir()
            cacheDir.listFiles()?.forEach { it.delete() }

            // 更新内存并写入空map到磁盘
            writeIndex(emptyMap())
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "清除所有缓存失败", e)
            false
        }
    }

    // 批量更新缓存的标题（用于孤立缓存的别名显示）
    fun updateCacheTitles(titlesMap: Map<String, String>) {
        val currentIndex = _index.value
        var changed = false
        val newIndex = currentIndex.toMutableMap()

        titlesMap.forEach { (url, title) ->
            val cache = newIndex[url]
            // 只有当缓存存在，且标题不一致时才更新
            if (cache != null && cache.title != title) {
                newIndex[url] = cache.copy(title = title)
                changed = true
            }
        }

        if (changed) {
            writeIndex(newIndex)
        }
    }

    private fun buildCompatKeys(primaryUrl: String, aliasUrls: List<String>): List<String> {
        return buildList {
            add(primaryUrl)
            aliasUrls.forEach { alias ->
                if (alias.isNotBlank()) add(alias)
            }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizedCacheTitle(title: String?): String? {
        return title
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun removePageFromIndex(
        index: MutableMap<String, CacheIndex>,
        novelUrl: String,
        pageNum: Int
    ) {
        val oldCache = index[novelUrl] ?: return
        val oldPages = oldCache.pages.toMutableMap()
        oldPages.remove(pageNum)

        if (oldPages.isEmpty()) {
            index.remove(novelUrl)
        } else {
            index[novelUrl] = oldCache.copy(pages = oldPages)
        }
    }

    /**
     * 把旧 key 的单页缓存迁移到 primaryUrl。
     *
     * 成功写入新文件后，删除旧文件和旧索引。
     * 如果旧文件删除失败，不强行移除旧索引，避免 index 指向状态不一致。
     */
    private suspend fun migratePageToPrimaryAndDeleteOld(
        primaryUrl: String,
        oldUrl: String,
        pageNum: Int,
        data: CacheData
    ): Boolean = withContext(Dispatchers.IO) {
        if (primaryUrl == oldUrl) return@withContext true

        try {
            val oldIndex = _index.value[oldUrl]
            val oldPageInfo = oldIndex?.pages?.get(pageNum)

            val jsonBytes = JSON.toJSONString(data).toByteArray(Charsets.UTF_8)
            val newFile = File(getCacheDir(), getCacheFileName(primaryUrl, pageNum))
            newFile.writeBytes(jsonBytes)

            val oldFile = File(getCacheDir(), getCacheFileName(oldUrl, pageNum))
            val oldDeleted = if (oldFile.exists()) oldFile.delete() else true
            if (!oldDeleted) {
                Log.w(LOG_TAG, "旧缓存文件删除失败，保留旧索引: $oldUrl page=$pageNum")
                return@withContext false
            }

            val currentIndex = _index.value
            val newIndex = currentIndex.toMutableMap()

            val primaryCache = newIndex[primaryUrl] ?: CacheIndex()
            val primaryPages = primaryCache.pages.toMutableMap()
            primaryPages[pageNum] = CachePageInfo(
                pageNum = pageNum,
                hasImages = oldPageInfo?.hasImages ?: false,
                timestamp = oldPageInfo?.timestamp ?: System.currentTimeMillis(),
                fileSize = jsonBytes.size.toLong()
            )

            newIndex[primaryUrl] = primaryCache.copy(
                title = normalizedCacheTitle(oldIndex?.title) ?: primaryCache.title,
                pages = primaryPages
            )

            removePageFromIndex(newIndex, oldUrl, pageNum)

            writeIndex(newIndex)
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "迁移旧缓存失败: $oldUrl -> $primaryUrl page=$pageNum", e)
            false
        }
    }

    /**
     * 主 key 已经命中时，顺手清理同页旧 key。
     * 用于处理之前"只复制不删除"的版本残留。
     */
    private suspend fun cleanupAliasPageFiles(
        primaryUrl: String,
        pageNum: Int,
        aliasUrls: List<String>
    ) = withContext(Dispatchers.IO) {
        val currentIndex = _index.value
        val newIndex = currentIndex.toMutableMap()
        var changed = false

        aliasUrls
            .map { it.trim() }
            .filter { it.isNotBlank() && it != primaryUrl }
            .distinct()
            .forEach { oldUrl ->
                val oldCache = newIndex[oldUrl] ?: return@forEach
                if (!oldCache.pages.containsKey(pageNum)) return@forEach

                val oldFile = File(getCacheDir(), getCacheFileName(oldUrl, pageNum))
                val deleted = if (oldFile.exists()) oldFile.delete() else true

                if (deleted) {
                    removePageFromIndex(newIndex, oldUrl, pageNum)
                    changed = true
                } else {
                    Log.w(LOG_TAG, "清理旧缓存文件失败，保留旧索引: $oldUrl page=$pageNum")
                }
            }

        if (changed) {
            writeIndex(newIndex)
        }
    }

    private suspend fun ensurePrimaryPageIndexed(
        primaryUrl: String,
        pageNum: Int,
        data: CacheData,
        fallbackTitle: String? = null,
        fallbackHasImages: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val currentIndex = _index.value
        val currentCache = currentIndex[primaryUrl]
        if (currentCache?.pages?.containsKey(pageNum) == true) return@withContext

        val cacheFile = File(getCacheDir(), getCacheFileName(primaryUrl, pageNum))
        val fileSize = if (cacheFile.exists()) {
            cacheFile.length().takeIf { it > 0L } ?: JSON.toJSONString(data).toByteArray(Charsets.UTF_8).size.toLong()
        } else {
            JSON.toJSONString(data).toByteArray(Charsets.UTF_8).size.toLong()
        }

        val newIndex = currentIndex.toMutableMap()
        val primaryCache = newIndex[primaryUrl] ?: CacheIndex()
        val primaryPages = primaryCache.pages.toMutableMap()

        primaryPages[pageNum] = CachePageInfo(
            pageNum = pageNum,
            hasImages = fallbackHasImages,
            timestamp = System.currentTimeMillis(),
            fileSize = fileSize
        )

        newIndex[primaryUrl] = primaryCache.copy(
            title = normalizedCacheTitle(fallbackTitle) ?: primaryCache.title,
            pages = primaryPages
        )

        writeIndex(newIndex)
    }

    /**
     * 兼容读取页面缓存。
     *
     * 1. 优先读取 primaryUrl。
     * 2. primaryUrl 命中时，顺手清理同页 alias 旧缓存。
     * 3. primaryUrl 未命中时，尝试 aliasUrls。
     * 4. alias 命中后，迁移到 primaryUrl，并在新文件写入成功后删除旧文件和旧索引。
     */
    suspend fun loadPageCompat(
        primaryUrl: String,
        pageNum: Int,
        aliasUrls: List<String> = emptyList()
    ): CacheData? = withContext(Dispatchers.IO) {
        val keys = buildCompatKeys(primaryUrl, aliasUrls)
        val aliases = keys.drop(1)

        // 1. 主 key 优先
        val primaryData = loadPage(primaryUrl, pageNum)
        if (primaryData != null) {
            val aliasWithIndex = aliases.firstNotNullOfOrNull { alias ->
                index.value[alias]?.pages?.get(pageNum)?.let { alias to it }
            }

            ensurePrimaryPageIndexed(
                primaryUrl = primaryUrl,
                pageNum = pageNum,
                data = primaryData,
                fallbackTitle = aliasWithIndex?.first?.let { index.value[it]?.title },
                fallbackHasImages = aliasWithIndex?.second?.hasImages ?: false
            )

            cleanupAliasPageFiles(primaryUrl, pageNum, aliases)
            return@withContext primaryData
        }

        // 2. fallback 旧 key；命中后迁移并删除旧文件
        for (oldUrl in aliases) {
            val data = loadPage(oldUrl, pageNum) ?: continue

            migratePageToPrimaryAndDeleteOld(
                primaryUrl = primaryUrl,
                oldUrl = oldUrl,
                pageNum = pageNum,
                data = data
            )

            return@withContext data
        }

        null
    }

    /**
     * 合并主 key 和旧 key 下的缓存页列表。
     * 用于 ReaderPage 的 cachedPages 显示。
     */
    fun getCachedPageNumsCompat(
        primaryUrl: String,
        aliasUrls: List<String> = emptyList()
    ): Set<Int> {
        val result = linkedSetOf<Int>()

        buildCompatKeys(primaryUrl, aliasUrls).forEach { key ->
            index.value[key]?.pages?.keys?.let { result.addAll(it) }
        }

        return result
    }

    /**
     * 删除主 key 和旧 key 下的同一页缓存。
     */
    suspend fun deletePageCompat(
        primaryUrl: String,
        pageNum: Int,
        aliasUrls: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        var success = true

        buildCompatKeys(primaryUrl, aliasUrls).forEach { key ->
            success = deletePage(key, pageNum) && success
        }

        success
    }

    /**
     * 删除主 key 和旧 key 下的整本缓存。
     */
    suspend fun deleteNovelCompat(
        primaryUrl: String,
        aliasUrls: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        var success = true

        buildCompatKeys(primaryUrl, aliasUrls).forEach { key ->
            success = deleteNovel(key) && success
        }

        success
    }

    /**
     * 按 normalized key 更新缓存标题。
     * 这样旧 key 下的缓存也能显示收藏标题。
     */
    fun updateCacheTitlesCompat(
        titlesMap: Map<String, String>,
        normalizeUrl: (String) -> String
    ) {
        val currentIndex = _index.value
        var changed = false
        val newIndex = currentIndex.toMutableMap()

        currentIndex.keys.forEach { cacheUrl ->
            val normalizedCacheUrl = normalizeUrl(cacheUrl)
            val title = titlesMap[cacheUrl] ?: titlesMap[normalizedCacheUrl]

            if (title != null) {
                val cache = newIndex[cacheUrl]
                if (cache != null && cache.title != title) {
                    newIndex[cacheUrl] = cache.copy(title = title)
                    changed = true
                }
            }
        }

        if (changed) {
            writeIndex(newIndex)
        }
    }
}