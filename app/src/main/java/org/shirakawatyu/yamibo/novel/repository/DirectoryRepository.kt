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

    // 条带锁：固定 32 个槽位，兼顾内存占用与并发性能
    private val STRIPE_COUNT = 32
    private val locks = Array(STRIPE_COUNT) { Mutex() }
    private fun getFileLock(name: String) =
        locks[(name.hashCode() and Int.MAX_VALUE) % STRIPE_COUNT]

    // 内存 LRU 缓存：保持 50 部漫画对象，使用同步装饰器确保线程安全
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

    // novel/repository/DirectoryRepository.kt

    /**
     * 核心动作 1：初次进入帖子。
     * 逻辑：无论是否有缓存，都尝试提取当前页超链接并合并，实现“动态补完”目录。
     */
    /**
     * 核心动作 1：初次进入帖子。
     */
    suspend fun initDirectoryForThread(
        tid: String,
        currentUrl: String,
        rawTitle: String,
        mobileHtml: String
    ): MangaDirectory {
        val cleanName = MangaTitleCleaner.getCleanBookName(rawTitle)

        return getFileLock(cleanName).withLock {
            val cachedDir = loadDirectory(cleanName)
            val samePageLinks = MangaHtmlParser.extractSamePageLinks(mobileHtml)

            val currentChapter = MangaChapterItem(
                tid = tid,
                rawTitle = rawTitle,
                chapterNum = MangaTitleCleaner.extractChapterNum(rawTitle),
                url = currentUrl,
                authorUid = null,
                authorName = null
            )

            val gatheredFromPage = (samePageLinks + currentChapter)

            if (cachedDir != null) {
                // 【情况 A】：已有缓存 -> 执行“补充合并”
                // 核心修复：白嫖的数据只作为补充，不覆盖已有的缓存数据！
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

                // 策略升级
                val newStrategy =
                    if (cachedDir.strategy == DirectoryStrategy.PENDING_SEARCH && samePageLinks.isNotEmpty()) {
                        DirectoryStrategy.LINKS
                    } else cachedDir.strategy

                val updatedDir = cachedDir.copy(
                    chapters = mergedChapters,
                    strategy = newStrategy
                )

                // 只有真的补充了新章节，或者策略改变了，才触发磁盘保存
                if (supplementaryChapters.isNotEmpty() || updatedDir.strategy != cachedDir.strategy) {
                    saveDirectory(updatedDir)
                }
                return@withLock updatedDir
            } else {
                // 【情况 B】：完全没缓存 -> 按照原有优先级创建
                val tagIds = MangaHtmlParser.findTagIdsMobile(mobileHtml)

                val strategy: DirectoryStrategy
                val sourceKey: String

                if (tagIds.isNotEmpty()) {
                    strategy = DirectoryStrategy.TAG
                    sourceKey = tagIds.joinToString(",")
                } else if (samePageLinks.isNotEmpty()) {
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

    suspend fun manuallyUpdateDirectory(
        currentDir: MangaDirectory,
        forceSearch: Boolean = false
    ): Result<DirectoryUpdateResult> =
        withContext(Dispatchers.IO) {
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
                            if (total > 1) for (p in 2..total) newChapters.addAll(
                                MangaHtmlParser.parseListHtml(
                                    mangaApi.getTagPageHtml(tagId, p).string(),
                                    index
                                )
                            )
                        }
                    }
                    if (newChapters.isEmpty()) {
                        searchPerformed = true
                        val res = performSearch(currentDir.cleanBookName)
                        if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
                        newChapters.addAll(res.getOrNull()!!)
                    }
                } else {
                    searchPerformed = true
                    val res = performSearch(currentDir.sourceKey)
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

    private suspend fun performSearch(keyword: String): Result<List<MangaChapterItem>> {
        val now = System.currentTimeMillis()
        if (now - GlobalData.lastSearchTimestamp.get() < 30_000L) return Result.failure(Exception("搜索冷却中"))
        GlobalData.lastSearchTimestamp.set(now)

        val safeKeyword = MangaTitleCleaner.getSearchKeyword(keyword)

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

        // 3. 【核心修复】：基于发帖时间线的话数兜底注入
        // 抓下来的 allItems 默认是【最新发帖在前】(时间倒序 / 话数降序)
        // 我们将其反转成【最老发帖在前】(时间正序 / 话数升序)，重现真实的汉化发布流程
        val ascendingItems = allItems.reversed()

        var lastValidNum = 0f
        var subIndex = 1

        val fixedItems = ascendingItems.map { item ->
            // 情况 A：能够精准解析出话数 (比如 第32话) -> 把它设为新的时间线锚点
            if (item.chapterNum > 0f && item.chapterNum < 1000f) {
                lastValidNum = item.chapterNum
                subIndex = 1 // 充当基准点，重置计数器
                item
            }
            // 情况 B：完全解析不出话数 (比如 "野猫驯养日记"，提取为 0f)
            else if (item.chapterNum == 0f) {
                // 精髓：根据发帖时间赋予微小小数。比如跟在 32话 后面的无编号帖子会被赋为 32.001话
                // 如果紧接着又是一篇无编号，则是 32.002话，极其完美的契合时间排序！
                val virtualNum = lastValidNum + (subIndex * 0.001f)
                subIndex++
                item.copy(chapterNum = virtualNum)
            }
            // 情况 C：带"番外/特典"的高编号 (提取为 1000f 以上)
            else {
                // 依然加上微小的发帖时间偏置，这样发多篇番外也会按发帖顺序稳稳排列
                val virtualNum = item.chapterNum + (subIndex * 0.001f)
                subIndex++
                item.copy(chapterNum = virtualNum)
            }
        }

        // 最终返回，因为 `mergeAndSortChapters` 后续会利用我们注入好的 chapterNum 进行强排序
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
}