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
    suspend fun initDirectoryForThread(
        tid: String,
        currentUrl: String,
        rawTitle: String,
        mobileHtml: String
    ): MangaDirectory {
        val cleanName = MangaTitleCleaner.getCleanBookName(rawTitle)

        return getFileLock(cleanName).withLock {
            // 1. 获取现有缓存
            val cachedDir = loadDirectory(cleanName)

            // 2. 【核心修复】：无论是否有缓存，每次进入都尝试提取当前页面的“白嫖”超链接
            val samePageLinks = MangaHtmlParser.extractSamePageLinks(mobileHtml)

            // 3. 构建当前话对象
            val currentChapter = MangaChapterItem(
                tid = tid,
                rawTitle = rawTitle,
                chapterNum = MangaTitleCleaner.extractChapterNum(rawTitle),
                url = currentUrl,
                authorUid = null,
                authorName = null
            )

            // 4. 将“当前话”和“白嫖到的链接”汇总
            val gatheredFromPage = (samePageLinks + currentChapter)

            if (cachedDir != null) {
                // 【情况 A】：已有缓存 -> 执行合并。
                // 使用 mergeAndSortChapters 确保 TID 去重且排序正确
                val mergedChapters = mergeAndSortChapters(cachedDir.chapters, gatheredFromPage)

                // 策略升级：如果原来是 PENDING（啥都没搜到），现在白嫖到了链接，升级为 LINKS 策略
                val newStrategy =
                    if (cachedDir.strategy == DirectoryStrategy.PENDING_SEARCH && samePageLinks.isNotEmpty()) {
                        DirectoryStrategy.LINKS
                    } else cachedDir.strategy

                val updatedDir = cachedDir.copy(
                    chapters = mergedChapters,
                    strategy = newStrategy
                )

                // 只有当数据真的发生变化（抓到了新东西）时才保存磁盘，减少 IO
                if (updatedDir.chapters.size > cachedDir.chapters.size || updatedDir.strategy != cachedDir.strategy) {
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
                        compareBy(
                            { it.groupIndex },
                            { it.chapterNum })
                    )
                )

                saveDirectory(newDir)
                return@withLock newDir
            }
        }
    }

    suspend fun manuallyUpdateDirectory(currentDir: MangaDirectory): Result<DirectoryUpdateResult> =
        withContext(Dispatchers.IO) {
            val newChapters = mutableListOf<MangaChapterItem>()
            var searchPerformed = false
            try {
                if (currentDir.strategy == DirectoryStrategy.TAG) {
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
        val html = mangaApi.searchForum(keyword = keyword).string()
        if (MangaHtmlParser.isFloodControlOrError(html)) return Result.failure(Exception("防灌水限制"))
        return Result.success(MangaHtmlParser.parseListHtml(html))
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