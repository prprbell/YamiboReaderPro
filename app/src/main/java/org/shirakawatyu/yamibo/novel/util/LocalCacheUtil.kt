package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.util.Log
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地缓存工具类
 * 管理小说页面的持久化缓存
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
            // [NEW] 修复fileSize为0的情况
            val fixedIndex = fixFileSizesIfNeeded(loadedIndex)
            _index.value = fixedIndex
        }
    }

    // 缓存索引数据类
    data class CacheIndex(
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
                object : com.alibaba.fastjson2.TypeReference<Map<String, CacheIndex>>() {}
            )
            map ?: emptyMap()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "从磁盘读取缓存索引失败", e)
            emptyMap()
        }
    }

    // [NEW] 修复索引中fileSize为0的情况
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
            try {
                val indexFile = getIndexFile()
                val jsonStr = JSON.toJSONString(fixedIndex)
                indexFile.writeText(jsonStr)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "保存修复后的索引失败", e)
            }
        }

        fixedIndex
    }

    // 写入索引：首先更新内存Flow，然后异步写入磁盘
    private fun writeIndex(newIndex: Map<String, CacheIndex>) {
        // 1. 立即更新内存 (这将通知 FavoriteVM)
        _index.value = newIndex

        // 2. 启动一个后台任务将新索引写入磁盘（用于持久化）
        ioScope.launch {
            try {
                val indexFile = getIndexFile()
                val jsonStr = JSON.toJSONString(newIndex)
                indexFile.writeText(jsonStr)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "将缓存索引写入磁盘失败", e)
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
        hasImages: Boolean
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

            // 基于内存中的 _index.value 进行更新，而不是从磁盘读取
            val currentIndex = _index.value
            val newIndex = currentIndex.toMutableMap()
            val novelCache = newIndex.getOrPut(novelUrl) { CacheIndex() }

            // 创建一个新的 pages map 来确保 StateFlow 认为对象已改变
            val newPages = novelCache.pages.toMutableMap()
            newPages[pageNum] = CachePageInfo(
                pageNum = pageNum,
                hasImages = hasImages,
                timestamp = System.currentTimeMillis(),
                fileSize = fileSize // 写入准确的大小
            )

            // 用新的 pages map 更新 index
            newIndex[novelUrl] = novelCache.copy(pages = newPages)

            // 调用新的 writeIndex (它会立即更新Flow)
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

    // 检查页面是否已缓存(从内存读取)
    suspend fun isPageCached(novelUrl: String, pageNum: Int): Boolean {
        return _index.value[novelUrl]?.pages?.containsKey(pageNum) ?: false
    }

    // 获取已缓存的页面列表(从内存读取)
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

    // 获取所有已缓存的小说URL列表(从内存读取)
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
}