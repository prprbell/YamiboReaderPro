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

    private val STRIPE_COUNT = 32
    private val locks = Array(STRIPE_COUNT) { Mutex() }
    private fun getFileLock(name: String) =
        locks[(name.hashCode() and Int.MAX_VALUE) % STRIPE_COUNT]

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
            Unit
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Save failed: $name", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    // 基于 TID 排序并重组序号
    private fun mergeAndSortChapters(
        old: List<MangaChapterItem>,
        new: List<MangaChapterItem>
    ): List<MangaChapterItem> {
        val map = LinkedHashMap<String, MangaChapterItem>()

        old.forEach { map[it.tid] = it }
        new.forEach { map[it.tid] = it }

        val sortedByTid = map.values.sortedBy { it.tid.toLongOrNull() ?: 0L }

        val extractedChapters = sortedByTid.map { item ->
            val extractedNum = MangaTitleCleaner.extractChapterNum(item.rawTitle)
            item.copy(chapterNum = extractedNum)
        }

        return smoothChapterDisplayNumbers(extractedChapters)
    }

    /**
     * 话数纠错
     */
    private fun smoothChapterDisplayNumbers(items: List<MangaChapterItem>): List<MangaChapterItem> {
        if (items.size < 3) return items

        val processed = items.toMutableList()
        val isBad = BooleanArray(processed.size)

        for (i in processed.indices) {
            val curr = processed[i].chapterNum
            if (curr <= 0f || curr >= 999f) continue

            var leftNum = -1f
            for (j in i - 1 downTo 0) {
                if (!isBad[j] && processed[j].chapterNum > 0f && processed[j].chapterNum < 999f) {
                    leftNum = processed[j].chapterNum
                    break
                }
            }

            if (leftNum != -1f && curr < leftNum - 3f) {
                isBad[i] = true
                continue
            }

            if (leftNum != -1f && curr > leftNum + 3f) {
                var dropCount = 0
                for (j in i + 1 until minOf(processed.size, i + 8)) {
                    val futureNum = processed[j].chapterNum
                    if (futureNum > 0f && futureNum < curr - 5f) {
                        dropCount++
                        if (dropCount >= 3) {
                            isBad[i] = true
                            break
                        }
                    }
                }
                if (isBad[i]) continue
            }

            if (leftNum == -1f) {
                var dropCount = 0
                for (j in i + 1 until minOf(processed.size, i + 8)) {
                    if (processed[j].chapterNum > 0f && processed[j].chapterNum < curr - 3f) {
                        dropCount++
                        if (dropCount >= 3) {
                            isBad[i] = true
                            break
                        }
                    }
                }
            }
        }

        for (i in processed.indices) {
            if (isBad[i]) {
                val currItem = processed[i]

                var prevValidNum = 0f
                for (j in i - 1 downTo 0) if (!isBad[j] && processed[j].chapterNum > 0f && processed[j].chapterNum < 999f) { prevValidNum = processed[j].chapterNum; break }

                var nextValidNum = 9999f
                for (j in i + 1 until processed.size) if (!isBad[j] && processed[j].chapterNum > 0f && processed[j].chapterNum < 999f) { nextValidNum = processed[j].chapterNum; break }

                val candidates = MangaTitleCleaner.extractAllPossibleNumbers(currItem.rawTitle)
                var bestFit = -1f

                val beautyComparator = compareBy<Float> { num ->
                    val str = java.text.DecimalFormat("0.###").format(num)
                    if (str.contains(".")) str.substringAfter(".").length else 0
                }.thenBy { it }

                val validCandidates = candidates.filter { it >= prevValidNum && it <= nextValidNum }

                if (validCandidates.isNotEmpty()) {
                    val strictGreater = validCandidates.filter { it > prevValidNum }
                    bestFit = if (strictGreater.isNotEmpty()) {
                        strictGreater.minWithOrNull(beautyComparator) ?: -1f
                    } else {
                        validCandidates.minWithOrNull(beautyComparator) ?: -1f
                    }
                }

                if (bestFit == -1f) {
                    var smartFill = -1f

                    if (prevValidNum > 0f && nextValidNum < 9999f) {
                        val expectedInt = kotlin.math.floor(prevValidNum.toDouble()).toFloat() + 1f

                        if (expectedInt > prevValidNum && expectedInt < nextValidNum && (nextValidNum - prevValidNum) <= 3.5f) {
                            val existsGlobally = processed.any { it.chapterNum == expectedInt }
                            val someoneElseClaimsIt = items.any {
                                it.tid != currItem.tid &&
                                        MangaTitleCleaner.extractAllPossibleNumbers(it.rawTitle).contains(expectedInt)
                            }

                            if (!existsGlobally && !someoneElseClaimsIt) {
                                smartFill = expectedInt
                            }
                        }
                    }

                    if (smartFill != -1f) {
                        bestFit = smartFill
                    } else if (candidates.isNotEmpty()) {
                        bestFit = candidates.minWithOrNull(beautyComparator) ?: candidates.first()
                    }
                }

                // 最终回填
                if (bestFit != -1f) {
                    val formattedNum = Math.round(bestFit * 1000) / 1000f
                    processed[i] = currItem.copy(chapterNum = formattedNum)
                    isBad[i] = false
                } else {
                    val safeBase = if (i > 0 && processed[i - 1].chapterNum > 0f && processed[i - 1].chapterNum < 999f) {
                        processed[i - 1].chapterNum
                    } else {
                        prevValidNum
                    }
                    val fallbackNum = Math.round((safeBase + 0.001f) * 1000) / 1000f
                    processed[i] = currItem.copy(chapterNum = fallbackNum)
                    isBad[i] = false
                }
            }
        }

        return processed
    }

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

            // 直接提取当前页超链接（无需再做虚拟话数补全）
            val rawSamePageLinks = MangaHtmlParser.extractSamePageLinks(mobileHtml)

            val currentChapter = MangaChapterItem(
                tid = tid,
                rawTitle = threadTitle,
                chapterNum = MangaTitleCleaner.extractChapterNum(threadTitle),
                url = currentUrl,
                authorUid = null,
                authorName = null
            )

            val gatheredFromPage = (rawSamePageLinks + currentChapter).distinctBy { it.tid }

            if (cachedDir != null) {
                val newStrategy =
                    if (cachedDir.strategy == DirectoryStrategy.PENDING_SEARCH && rawSamePageLinks.isNotEmpty()) {
                        DirectoryStrategy.LINKS
                    } else cachedDir.strategy

                val existingTids = cachedDir.chapters.map { it.tid }.toSet()

                val supplementaryChapters = gatheredFromPage.filter { it.tid !in existingTids }

                val mergedChapters = mergeAndSortChapters(cachedDir.chapters, supplementaryChapters)

                val updatedDir = cachedDir.copy(
                    chapters = mergedChapters,
                    strategy = newStrategy
                )

                if (mergedChapters.size != cachedDir.chapters.size || updatedDir.strategy != cachedDir.strategy) {
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

                // 调用核心 TID 排序初始化
                val initialChapters = mergeAndSortChapters(emptyList(), gatheredFromPage)

                val newDir = MangaDirectory(
                    cleanBookName = cleanName,
                    strategy = strategy,
                    sourceKey = sourceKey,
                    chapters = initialChapters
                )

                saveDirectory(newDir)
                return@withLock newDir
            }
        }
    }

    suspend fun manuallyUpdateDirectory(
        currentDir: MangaDirectory,
        forceSearch: Boolean = false,
        currentTid: String? = null
    ): Result<DirectoryUpdateResult> = withContext(Dispatchers.IO) {
        val newChapters = mutableListOf<MangaChapterItem>()
        var searchPerformed = false
        try {
            val targetChapter = currentDir.chapters.find { it.tid == currentTid }
                ?: currentDir.chapters.lastOrNull()

            val firstRawTitle = targetChapter?.rawTitle ?: currentDir.cleanBookName
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
                                        mangaApi.getTagPageHtml(tagId, p).string(), index
                                    )
                                )
                            }
                        }
                    }
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
                // 直接把新抓取的数据合并，交给TID排序
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

        return Result.success(allItems)
    }

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