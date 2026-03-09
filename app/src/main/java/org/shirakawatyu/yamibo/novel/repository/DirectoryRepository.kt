package org.shirakawatyu.yamibo.novel.repository

import android.content.Context
import android.util.Log
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy
import org.shirakawatyu.yamibo.novel.bean.MangaChapterItem
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.parser.MangaHtmlParser
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import java.io.File
import java.io.IOException
import java.util.Collections

data class DirectoryUpdateResult(val directory: MangaDirectory, val searchPerformed: Boolean)

class DirectoryRepository private constructor(private val context: Context) {
    private val mangaApi = YamiboRetrofit.getInstance().create(MangaApi::class.java)
    private val DIRECTORY_DIR = "manga_directory"
    private val LOG_TAG = "DirectoryRepo"

    // 条带锁：固定32个槽位
    private val STRIPE_COUNT = 32
    private val locks = Array(STRIPE_COUNT) { Mutex() }
    private fun getFileLock(name: String) =
        locks[(name.hashCode() and Int.MAX_VALUE) % STRIPE_COUNT]

    // 内存LRU缓存：保持50部漫画对象，使用同步装饰器确保线程安全
    private val memoryCache: MutableMap<String, MangaDirectory> = Collections.synchronizedMap(
        object : LinkedHashMap<String, MangaDirectory>(20, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MangaDirectory>?) =
                size > 50
        }
    )

    companion object {
        @Volatile
        private var instance: DirectoryRepository? = null
        fun getInstance(context: Context): DirectoryRepository = instance ?: synchronized(this) {
            instance ?: DirectoryRepository(context.applicationContext).also { instance = it }
        }
    }

    private fun getDirectoryFile(cleanName: String): File {
        val safeName = cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .let { if (it.length > 50) it.substring(0, 50) else it }
        return File(
            File(context.filesDir, DIRECTORY_DIR).apply { if (!exists()) mkdirs() },
            "${safeName}_${cleanName.hashCode().toString(16)}_dir.json"
        )
    }

    private suspend fun loadDirectory(cleanName: String): MangaDirectory? {
        memoryCache[cleanName]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val file = getDirectoryFile(cleanName)
                if (!file.exists()) return@withContext null
                JSON.parseObject(file.readText(), MangaDirectory::class.java)
                    .also { if (it != null) memoryCache[cleanName] = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun saveDirectory(directory: MangaDirectory) = withContext(Dispatchers.IO) {
        val name = directory.cleanBookName
        val file = getDirectoryFile(name)
        val tempFile = File(file.parent, "${file.name}.tmp")
        try {
            memoryCache[name] = directory
            tempFile.writeText(JSON.toJSONString(directory))
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            }
            Unit // 显式返回 Unit 解决 if-else 报错
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Save failed: $name", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * 核心动作 1：初次进入帖子。
     * 逻辑：无论是否有缓存，都尝试提取当前页超链接并合并，实现“动态补完”目录。
     */
    suspend fun initDirectoryForThread(
        tid: String,
        currentUrl: String,
        rawTitle: String,
        mobileHtml: String
    ): MangaDirectory {
        val threadTitle = MangaTitleCleaner.getCleanThreadTitle(rawTitle)
        val cleanName = MangaTitleCleaner.getCleanBookName(rawTitle)

        return getFileLock(cleanName).withLock {
            val cachedDir = loadDirectory(cleanName)

            val rawSamePageLinks = MangaHtmlParser.extractSamePageLinks(mobileHtml)

            val validNums = rawSamePageLinks.map { it.chapterNum }.filter { it > 0f && it < 1000f }
            val isDescending = validNums.size >= 2 && validNums.first() > validNums.last()

            val ascendingLinks = if (isDescending) rawSamePageLinks.reversed() else rawSamePageLinks

            var lastValidNum = 0f
            var subIndex = 1

            val fixedSamePageLinks = ascendingLinks.map { item ->
                if (item.chapterNum > 0f && item.chapterNum < 1000f) {
                    lastValidNum = item.chapterNum
                    subIndex = 1
                    item
                } else if (item.chapterNum == 0f) {
                    val virtualNum = lastValidNum + (subIndex * 0.001f)
                    subIndex++
                    item.copy(chapterNum = virtualNum)
                } else {
                    val virtualNum = item.chapterNum + (subIndex * 0.001f)
                    subIndex++
                    item.copy(chapterNum = virtualNum)
                }
            }

            val currentChapter = MangaChapterItem(
                tid = tid,
                rawTitle = threadTitle,
                chapterNum = MangaTitleCleaner.extractChapterNum(threadTitle),
                url = currentUrl,
                authorUid = null,
                authorName = null
            )

            val gatheredFromPage = (fixedSamePageLinks + currentChapter).distinctBy { it.tid }

            if (cachedDir != null) {
                val existingTids = cachedDir.chapters.map { it.tid }.toSet()
                val supplementaryChapters = gatheredFromPage.filter { it.tid !in existingTids }

                val mergedChapters = if (supplementaryChapters.isNotEmpty()) {
                    (cachedDir.chapters + supplementaryChapters).sortedWith(
                        compareBy(
                            { it.groupIndex },
                            { it.chapterNum })
                    )
                } else {
                    cachedDir.chapters
                }

                val newStrategy =
                    if (cachedDir.strategy == DirectoryStrategy.PENDING_SEARCH && rawSamePageLinks.isNotEmpty()) {
                        DirectoryStrategy.LINKS
                    } else cachedDir.strategy

                val updatedDir = cachedDir.copy(
                    chapters = mergedChapters,
                    strategy = newStrategy
                )

                if (supplementaryChapters.isNotEmpty() || updatedDir.strategy != cachedDir.strategy) {
                    saveDirectory(updatedDir)
                }
                return@withLock updatedDir
            } else {
                val tagIds = MangaHtmlParser.findTagIdsMobile(mobileHtml)

                val strategy: DirectoryStrategy
                val sourceKey: String

                if (tagIds.isNotEmpty()) {
                    strategy = DirectoryStrategy.TAG
                    sourceKey = tagIds.joinToString(",")
                } else if (rawSamePageLinks.isNotEmpty()) {
                    strategy = DirectoryStrategy.LINKS
                    sourceKey = cleanName
                } else {
                    strategy = DirectoryStrategy.PENDING_SEARCH
                    sourceKey = cleanName
                }

                val newDir = MangaDirectory(
                    cleanBookName = cleanName,
                    strategy = strategy,
                    sourceKey = sourceKey,
                    chapters = gatheredFromPage.sortedWith(
                        compareBy({ it.groupIndex }, { it.chapterNum })
                    )
                )

                saveDirectory(newDir)
                return@withLock newDir
            }
        }
    }

    /**
     * 通用时间线降级补完算法 (双向插值法)
     * 针对无法提取编号的帖子(0f)以及提取错误/排序差距过大的异常贴，
     * 严格根据它们在时间线上的真实位置，参考前后的正常编号，动态计算出一个合理的顺位编号。
     */
    private fun fixChaptersByTimeline(
        items: List<MangaChapterItem>,
        isSearchDesc: Boolean = false
    ): List<MangaChapterItem> {
        if (items.isEmpty()) return items

        // 1. 决定基准时间线排序 (最老发帖在前)
        val ascendingItems = if (items.any { it.publishTime > 0L }) {
            items.sortedBy { it.publishTime }
        } else {
            if (isSearchDesc) items.reversed() else items
        }

        // ======================= 阶段一：检测排序差距过大 =======================
        // 筛选出看起来处于正常区间的编号
        val rawValidItems = ascendingItems.filter { it.chapterNum > 0f && it.chapterNum < 1000f }
        val sortedRawValid = rawValidItems.sortedBy { it.chapterNum }
        val sortedIndexMap = sortedRawValid.mapIndexed { index, item -> item.tid to index }.toMap()

        // 容忍阈值：由于数字提取错乱导致排序偏离超过 3 话，视为提取错误，打入“冷宫”
        val DEVIATION_THRESHOLD = 3
        val anomalyTids = rawValidItems.mapIndexedNotNull { index, item ->
            val sortedIdx = sortedIndexMap[item.tid] ?: index
            if (Math.abs(index - sortedIdx) >= DEVIATION_THRESHOLD) item.tid else null
        }.toSet()

        // ======================= 阶段二：双向插值法赋予真实位置编号 =======================

        // 辅助函数1：判断是否是需要被赋予新编号的“坑位”
        fun isHole(item: MangaChapterItem): Boolean {
            val num = item.chapterNum
            return num == 0f || (num > 0f && num < 1000f && anomalyTids.contains(item.tid))
        }

        // 辅助函数2：判断是否是可靠的基准正常编号 (非0，非番外，非异常)
        fun isValidNormal(item: MangaChapterItem): Boolean {
            val num = item.chapterNum
            return num > 0f && num < 1000f && !anomalyTids.contains(item.tid)
        }

        val processedItems = ascendingItems.toMutableList()
        var i = 0
        while (i < processedItems.size) {
            if (isHole(processedItems[i])) {
                val holeStartIndex = i
                var holeEndIndex = i

                // 向后试探，找到连续挨在一起的所有“坑位”
                while (holeEndIndex + 1 < processedItems.size && isHole(processedItems[holeEndIndex + 1])) {
                    holeEndIndex++
                }

                val holeCount = holeEndIndex - holeStartIndex + 1

                // 向左寻找最近的可靠正常编号 (遇到番外会自动跳过)
                var prevValidNum = 0f
                for (j in holeStartIndex - 1 downTo 0) {
                    if (isValidNormal(processedItems[j])) {
                        prevValidNum = processedItems[j].chapterNum
                        break
                    }
                }

                // 向右寻找最近的可靠正常编号 (遇到番外会自动跳过)
                var nextValidNum = -1f
                for (j in holeEndIndex + 1 until processedItems.size) {
                    if (isValidNormal(processedItems[j])) {
                        nextValidNum = processedItems[j].chapterNum
                        break
                    }
                }

                // 计算填坑步长
                val step = if (nextValidNum != -1f) {
                    // 场景A：被夹在两个正常编号中间，平分这个区间
                    // 例如夹在 2 和 4 之间，有 1 个坑位：(4 - 2) / 2 = 1.0，坑位将被赋予 3
                    (nextValidNum - prevValidNum) / (holeCount + 1)
                } else {
                    // 场景B：右侧已经没有正常编号了（末尾），默认以 1 为步长自增
                    // 例如上一话是 5，后面跟着两个坑位，则分别赋予 6、7
                    1.0f
                }

                // 为这一段连续的坑位依次赋予计算出的真实顺位编号
                for (k in 0 until holeCount) {
                    val assignedNum = prevValidNum + step * (k + 1)
                    // 保留三位小数，防止浮点精度溢出 (如 2.333333 变为 2.333)
                    val formattedNum = Math.round(assignedNum * 1000) / 1000f
                    processedItems[holeStartIndex + k] =
                        processedItems[holeStartIndex + k].copy(chapterNum = formattedNum)
                }

                i = holeEndIndex + 1 // 跳过已处理的坑位块
            } else {
                i++
            }
        }

        return processedItems
    }

    suspend fun manuallyUpdateDirectory(
        currentDir: MangaDirectory,
        forceSearch: Boolean = false
    ): Result<DirectoryUpdateResult> = withContext(Dispatchers.IO) {
        val newChapters = mutableListOf<MangaChapterItem>()
        var searchPerformed = false
        try {
            if (!forceSearch && currentDir.strategy == DirectoryStrategy.TAG) {
                val tagIdList = currentDir.sourceKey.split(",")
                for ((index, tagId) in tagIdList.withIndex()) {
                    if (tagId.isBlank()) continue
                    val html1 = mangaApi.getTagPageHtml(tagId, 1).string()
                    val parsed = MangaHtmlParser.parseListHtml(html1, index)
                    if (parsed.isNotEmpty()) {
                        newChapters.addAll(parsed)
                        val total = MangaHtmlParser.extractTotalPages(html1)
                        if (total > 1) {
                            for (p in 2..total) {
                                newChapters.addAll(
                                    MangaHtmlParser.parseListHtml(
                                        mangaApi.getTagPageHtml(
                                            tagId,
                                            p
                                        ).string(), index
                                    )
                                )
                            }
                        }
                    }
                }

                // TAG 爬取完毕后，应用时间线兜底排序机制！
                if (newChapters.isNotEmpty()) {
                    val fixedChapters = fixChaptersByTimeline(newChapters, isSearchDesc = false)
                    newChapters.clear()
                    newChapters.addAll(fixedChapters)
                }

                if (newChapters.isEmpty()) {
                    searchPerformed = true
                    val rawTitleForSearch =
                        currentDir.chapters.firstOrNull()?.rawTitle ?: currentDir.cleanBookName
                    val res = performSearch(rawTitleForSearch)
                    if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
                    newChapters.addAll(res.getOrNull()!!)
                }
            } else {
                searchPerformed = true
                val rawTitleForSearch =
                    currentDir.chapters.firstOrNull()?.rawTitle ?: currentDir.cleanBookName
                val res = performSearch(rawTitleForSearch)
                if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
                newChapters.addAll(res.getOrNull()!!)
            }

            val finalDir = getFileLock(currentDir.cleanBookName).withLock {
                val latest = loadDirectory(currentDir.cleanBookName) ?: currentDir
                val merged = mergeAndSortChapters(latest.chapters, newChapters)
                val updated = latest.copy(
                    chapters = merged,
                    lastUpdateTime = System.currentTimeMillis(),
                    strategy = if (latest.strategy != DirectoryStrategy.TAG) DirectoryStrategy.SEARCHED else latest.strategy
                )
                saveDirectory(updated)
                updated
            }
            Result.success(DirectoryUpdateResult(finalDir, searchPerformed))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun performSearch(rawTitle: String): Result<List<MangaChapterItem>> {
        val now = System.currentTimeMillis()
        if (now - GlobalData.lastSearchTimestamp.get() < 30_000L) return Result.failure(Exception("搜索冷却中"))
        GlobalData.lastSearchTimestamp.set(now)

        val safeKeyword = MangaTitleCleaner.getSearchKeyword(rawTitle)

        // 1. 获取第一页数据
        val firstPageHtml = mangaApi.searchForum(keyword = safeKeyword).string()
        if (MangaHtmlParser.isFloodControlOrError(firstPageHtml)) return Result.failure(Exception("防灌水限制"))

        val allItems = mutableListOf<MangaChapterItem>()
        allItems.addAll(MangaHtmlParser.parseListHtml(firstPageHtml))

        // 2. 【翻页逻辑】：如果有更多页，拿到 searchId 把后面所有的帖子全拉下来
        val totalPages = MangaHtmlParser.extractTotalPages(firstPageHtml)
        val searchId = MangaHtmlParser.extractSearchId(firstPageHtml)

        if (searchId != null && totalPages > 1) {
            for (p in 2..totalPages) {
                // 翻页使用的是 searchid，基本不会触发 30秒搜索冷却机制
                val pageHtml = mangaApi.searchForumPage(searchid = searchId, page = p).string()
                allItems.addAll(MangaHtmlParser.parseListHtml(pageHtml))
            }
        }

        val fixedItems = fixChaptersByTimeline(allItems, isSearchDesc = true)

        return Result.success(fixedItems)
    }

    private fun mergeAndSortChapters(
        old: List<MangaChapterItem>,
        new: List<MangaChapterItem>
    ): List<MangaChapterItem> {
        val map = LinkedHashMap<String, MangaChapterItem>()
        val validNums = new.map { it.chapterNum }.toSet()
        old.forEach {
            if (!(validNums.contains(it.chapterNum) && new.none { n -> n.tid == it.tid })) map[it.tid] =
                it
        }
        new.forEach { map[it.tid] = it }
        return map.values.sortedWith(compareBy({ it.groupIndex }, { it.chapterNum }))
    }
    // ==================== 目录管理功能 ====================

    /**
     * 获取所有本地保存的目录
     */
    suspend fun getAllDirectories(): List<MangaDirectory> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIRECTORY_DIR)
        if (!dir.exists()) return@withContext emptyList()

        val files = dir.listFiles { _, name -> name.endsWith("_dir.json") }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            try {
                JSON.parseObject(file.readText(), MangaDirectory::class.java).also {
                    // 同步到内存缓存
                    if (it != null) memoryCache[it.cleanBookName] = it
                }
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.lastUpdateTime } // 按最后更新时间降序排列
    }

    /**
     * 删除指定漫画的本地目录
     */
    suspend fun deleteDirectory(cleanName: String): Boolean = withContext(Dispatchers.IO) {
        getFileLock(cleanName).withLock {
            memoryCache.remove(cleanName)
            val file = getDirectoryFile(cleanName)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        }
    }

    /**
     * 清空所有本地目录
     */
    suspend fun clearAllDirectories(): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIRECTORY_DIR)
        if (!dir.exists()) return@withContext true

        memoryCache.clear()
        val files =
            dir.listFiles { _, name -> name.endsWith("_dir.json") } ?: return@withContext true
        var allDeleted = true
        for (file in files) {
            if (!file.delete()) allDeleted = false
        }
        allDeleted
    }
}