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
import kotlin.math.abs

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
     * 初次进入帖子。
     * 无论是否有缓存，都尝试提取当前页超链接并合并
     */
    suspend fun initDirectoryForThread(
        tid: String,
        currentUrl: String,
        rawTitle: String,
        mobileHtml: String
    ): MangaDirectory {
        val threadTitle = MangaTitleCleaner.getCleanThreadTitle(rawTitle)
        val allDirs = getAllDirectories()
        val existingDir = allDirs.find { dir -> dir.chapters.any { it.tid == tid } }

        val cleanName = existingDir?.cleanBookName ?: MangaTitleCleaner.getCleanBookName(rawTitle)

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
     * 差分法：利用相邻帖子的标题相似性，拯救正则提取失败的章节
     */
    private fun rescueChapterNumsByDiff(items: List<MangaChapterItem>): List<MangaChapterItem> {
        if (items.size < 3) return items

        val processed = items.toMutableList()

        // 滑动窗口对比相邻的 3 个标题
        for (i in 1 until processed.size - 1) {
            val prev = processed[i - 1]
            val curr = processed[i]
            val next = processed[i + 1]

            // 只有当当前话数提取失败（即 0f 坑位），才触发救援
            if (curr.chapterNum == 0f) {
                val prefix = curr.rawTitle.commonPrefixWith(prev.rawTitle)
                val suffix = curr.rawTitle.commonSuffixWith(next.rawTitle)

                // 如果前后缀重合度很高（说明楼主排版格式非常固定）
                if (prefix.length > 3 || suffix.length > 2) {
                    // 砍掉公共前后缀，只留中间的差异字符
                    val diffStr = curr.rawTitle.removePrefix(prefix).removeSuffix(suffix).trim()

                    // 尝试将提取出的差异部分转为数字
                    val rescuedNum = diffStr.toFloatOrNull()
                    if (rescuedNum != null && rescuedNum > 0f && rescuedNum < 1000f) {
                        processed[i] = curr.copy(chapterNum = rescuedNum)
                    }
                }
            }
        }
        return processed
    }
    /**
     * 补完算法
     * 针对无法提取编号的帖子以及提取错误/排序差距过大的异常贴，
     * 根据它们在时间线上的真实位置，参考前后的正常编号，动态计算出一个合理的顺位编号。
     */
    private fun fixChaptersByTimeline(
        items: List<MangaChapterItem>,
        isSearchDesc: Boolean = false
    ): List<MangaChapterItem> {
        if (items.isEmpty()) return items

        // TID基准
        val ascendingItems = if (items.any { it.publishTime > 0L }) {
            // 时间戳优先，当时间戳相同或丢失精度时，用TID兜底
            items.sortedWith(compareBy({ it.publishTime }, { it.tid.toLongOrNull() ?: 0L }))
        } else {
            // 无时间戳时，纯靠TID排序
            val sortedByTid = items.sortedBy { it.tid.toLongOrNull() ?: 0L }
            if (isSearchDesc) sortedByTid.reversed() else sortedByTid
        }

        // 统计全局话数出现的频率
        val chapterNumFrequencies = ascendingItems
            .filter { it.chapterNum > 0f }
            .groupingBy { it.chapterNum }
            .eachCount()

        // 自顶向下的离群值裁剪
        val MAX_VALID_CHAPTER = 1000f
        var dynamicMaxBound = MAX_VALID_CHAPTER

        val uniqueDescNums = chapterNumFrequencies.keys.filter { it < MAX_VALID_CHAPTER }.sortedDescending()

        // 允许的顶部跳跃跨度阈值。
        val TOP_GAP_THRESHOLD = 5f

        if (uniqueDescNums.isNotEmpty()) {
            var validMax = uniqueDescNums.first()

            for (i in 0 until uniqueDescNums.size - 1) {
                val curr = uniqueDescNums[i]   // 当前最大
                val next = uniqueDescNums[i + 1] // 第二大

                // 如果最大值比第二大超出了合理范围
                if (curr - next > TOP_GAP_THRESHOLD) {
                    validMax = next
                } else {
                    validMax = curr
                    break
                }
            }
            dynamicMaxBound = validMax + TOP_GAP_THRESHOLD
        }

        // ======================= 阶段一：检测排序差距过大 =======================
        val rawValidItems = ascendingItems.filter { it.chapterNum > 0f && it.chapterNum < MAX_VALID_CHAPTER }
        val sortedRawValid = rawValidItems.sortedBy { it.chapterNum }
        val sortedIndexMap = sortedRawValid.mapIndexed { index, item -> item.tid to index }.toMap()

        val DEVIATION_THRESHOLD = 3
        val anomalyTids = rawValidItems.mapIndexedNotNull { index, item ->
            val sortedIdx = sortedIndexMap[item.tid] ?: index

            val isInteger = item.chapterNum % 1f == 0f
            val isNotTooLarge = item.chapterNum <= dynamicMaxBound
            val isUnique = chapterNumFrequencies[item.chapterNum] == 1

            val isTrustworthy = isInteger && isNotTooLarge && isUnique

            if (abs(index - sortedIdx) >= DEVIATION_THRESHOLD && !isTrustworthy) {
                item.tid
            } else {
                null
            }
        }.toSet()

        // ======================= 阶段二：双向插值法赋予真实位置编号 =======================

        // 判断是否是需要被赋予新编号的“坑位”
        fun isHole(item: MangaChapterItem): Boolean {
            val num = item.chapterNum
            return num == 0f || (num > 0f && num < 1000f && anomalyTids.contains(item.tid))
        }

        // 判断是否是可靠的基准正常编号
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

                // 向后试探
                while (holeEndIndex + 1 < processedItems.size && isHole(processedItems[holeEndIndex + 1])) {
                    holeEndIndex++
                }

                val holeCount = holeEndIndex - holeStartIndex + 1

                // 向左寻找
                var prevValidNum = 0f
                for (j in holeStartIndex - 1 downTo 0) {
                    if (isValidNormal(processedItems[j])) {
                        prevValidNum = processedItems[j].chapterNum
                        break
                    }
                }

                // 向右寻找
                var nextValidNum = -1f
                for (j in holeEndIndex + 1 until processedItems.size) {
                    if (isValidNormal(processedItems[j])) {
                        nextValidNum = processedItems[j].chapterNum
                        break
                    }
                }

                // 计算步长
                val step = if (nextValidNum != -1f) {
                    (nextValidNum - prevValidNum) / (holeCount + 1)
                } else {
                    1.0f
                }

                for (k in 0 until holeCount) {
                    val assignedNum = prevValidNum + step * (k + 1)
                    val formattedNum = Math.round(assignedNum * 1000) / 1000f
                    processedItems[holeStartIndex + k] =
                        processedItems[holeStartIndex + k].copy(chapterNum = formattedNum)
                }

                i = holeEndIndex + 1
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
            val firstRawTitle =
                currentDir.chapters.firstOrNull()?.rawTitle ?: currentDir.cleanBookName
            val autoCleanName = MangaTitleCleaner.getCleanBookName(firstRawTitle)
            val exactKeyword = currentDir.searchKeyword

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

                if (newChapters.isNotEmpty()) {
                    val rescuedChapters = rescueChapterNumsByDiff(newChapters)
                    val fixedChapters = fixChaptersByTimeline(rescuedChapters, isSearchDesc = false)
                    newChapters.clear()
                    newChapters.addAll(fixedChapters)
                }

                if (newChapters.isEmpty()) {
                    searchPerformed = true
                    val res = performSearch(firstRawTitle, exactKeyword)
                    if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
                    newChapters.addAll(res.getOrNull()!!)
                }
            } else {
                searchPerformed = true
                val res = performSearch(firstRawTitle, exactKeyword)
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

    private suspend fun performSearch(
        rawTitle: String,
        exactKeyword: String? = null
    ): Result<List<MangaChapterItem>> {
        val now = System.currentTimeMillis()
        if (now - GlobalData.lastSearchTimestamp.get() < 20_000L) return Result.failure(Exception("搜索冷却中，请等待20秒"))
        GlobalData.lastSearchTimestamp.set(now)

        val safeKeyword = exactKeyword ?: MangaTitleCleaner.getSearchKeyword(rawTitle)

        val firstPageHtml = mangaApi.searchForum(keyword = safeKeyword).string()
        if (MangaHtmlParser.isFloodControlOrError(firstPageHtml)) return Result.failure(Exception("触发论坛防灌水限制，请稍后再试"))

        val allItems = mutableListOf<MangaChapterItem>()
        allItems.addAll(MangaHtmlParser.parseListHtml(firstPageHtml))

        val totalPages = MangaHtmlParser.extractTotalPages(firstPageHtml)
        val searchId = MangaHtmlParser.extractSearchId(firstPageHtml)

        if (searchId != null && totalPages > 1) {
            for (p in 2..totalPages) {
                val pageHtml = mangaApi.searchForumPage(searchid = searchId, page = p).string()
                allItems.addAll(MangaHtmlParser.parseListHtml(pageHtml))
            }
        }

        val rescuedItems = rescueChapterNumsByDiff(allItems)
        val fixedItems = fixChaptersByTimeline(rescuedItems, isSearchDesc = true)
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
                    if (it != null) memoryCache[it.cleanBookName] = it
                }
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.lastUpdateTime }
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

    suspend fun renameAndMergeDirectory(
        currentDir: MangaDirectory,
        newCleanName: String,
        newSearchKeyword: String
    ): MangaDirectory = withContext(Dispatchers.IO) {
        val oldName = currentDir.cleanBookName

        if (oldName == newCleanName && currentDir.searchKeyword == newSearchKeyword) return@withContext currentDir

        if (oldName == newCleanName) {
            getFileLock(oldName).withLock {
                val mergedDir = currentDir.copy(
                    searchKeyword = newSearchKeyword,
                    lastUpdateTime = System.currentTimeMillis()
                )
                saveDirectory(mergedDir)
                return@withLock mergedDir
            }
        } else {
            val lock1 = getFileLock(if (oldName < newCleanName) oldName else newCleanName)
            val lock2 = getFileLock(if (oldName < newCleanName) newCleanName else oldName)

            lock1.withLock {
                lock2.withLock {
                    val targetDir = loadDirectory(newCleanName)

                    val mergedChapters = if (targetDir != null) {
                        mergeAndSortChapters(targetDir.chapters, currentDir.chapters)
                    } else currentDir.chapters

                    val newStrategy = targetDir?.strategy ?: currentDir.strategy
                    val newSourceKey = targetDir?.sourceKey
                        ?: if (currentDir.strategy == DirectoryStrategy.TAG) currentDir.sourceKey else newCleanName

                    val mergedDir = MangaDirectory(
                        cleanBookName = newCleanName,
                        strategy = newStrategy,
                        sourceKey = newSourceKey,
                        chapters = mergedChapters,
                        lastUpdateTime = System.currentTimeMillis(),
                        searchKeyword = newSearchKeyword
                    )

                    saveDirectory(mergedDir)

                    val oldFile = getDirectoryFile(oldName)
                    if (oldFile.exists()) oldFile.delete()
                    memoryCache.remove(oldName)

                    return@withLock mergedDir
                }
            }
        }
    }
}