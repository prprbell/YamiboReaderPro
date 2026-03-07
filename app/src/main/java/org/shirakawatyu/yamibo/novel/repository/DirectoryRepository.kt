package org.shirakawatyu.yamibo.novel.repository

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.TypeReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy
import org.shirakawatyu.yamibo.novel.bean.MangaChapterItem
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.MangaApi
import org.shirakawatyu.yamibo.novel.parser.MangaHtmlParser
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner
import kotlin.coroutines.resume

class DirectoryRepository private constructor(private val context: Context) {
    private val mangaApi = YamiboRetrofit.getInstance().create(MangaApi::class.java)
    private val DIRECTORY_KEY = stringPreferencesKey("manga_directory_map")

    companion object {
        @Volatile
        private var instance: DirectoryRepository? = null
        fun getInstance(context: Context): DirectoryRepository =
            instance ?: synchronized(this) {
                instance ?: DirectoryRepository(context.applicationContext).also { instance = it }
            }
    }

    /**
     * 辅助方法：将 DataStoreUtil 的 Callback 回调转换为挂起函数，读取目录 Map
     */
    private suspend fun getDirectoryMap(): MutableMap<String, MangaDirectory> {
        return suspendCancellableCoroutine { continuation ->
            DataStoreUtil.getData(
                key = DIRECTORY_KEY,
                callback = { jsonString ->
                    try {
                        val type =
                            object : TypeReference<MutableMap<String, MangaDirectory>>() {}.type
                        val map = JSON.parseObject(jsonString, type)
                            ?: mutableMapOf<String, MangaDirectory>()
                        continuation.resume(map)
                    } catch (e: Exception) {
                        continuation.resume(mutableMapOf())
                    }
                },
                onNull = {
                    continuation.resume(mutableMapOf())
                }
            )
        }
    }

    /**
     * 辅助方法：将 DataStoreUtil 的 Callback 回调转换为挂起函数，保存目录 Map
     */
    private suspend fun saveDirectoryMap(map: Map<String, MangaDirectory>) {
        return suspendCancellableCoroutine { continuation ->
            val jsonString = JSON.toJSONString(map)
            DataStoreUtil.addData(jsonString, DIRECTORY_KEY) {
                continuation.resume(Unit)
            }
        }
    }

    /**
     * 核心动作 1：初次进入帖子时进行零开销探测 (无任何网络请求)
     */
    suspend fun initDirectoryForThread(
        tid: String,
        currentUrl: String,
        rawTitle: String,
        mobileHtml: String
    ): MangaDirectory {
        return withContext(Dispatchers.IO) {
            val cleanName = MangaTitleCleaner.getCleanBookName(rawTitle)

            // 【完美修补】1. 优先检查本地是否已经有完整缓存。
            val dirMap = getDirectoryMap()
            val cachedDir = dirMap[cleanName]

            if (cachedDir != null && cachedDir.chapters.isNotEmpty()) {
                // 检查当前用户所在的这一话，是否已经在我们的缓存目录里了
                val isCurrentChapterExists = cachedDir.chapters.any { it.tid == tid }

                if (!isCurrentChapterExists) {
                    // 如果不在，说明这是用户自己点进来的新话！直接“白嫖”进本地目录
                    val currentItem = MangaChapterItem(
                        tid = tid,
                        rawTitle = rawTitle,
                        chapterNum = MangaTitleCleaner.extractChapterNum(rawTitle),
                        url = currentUrl,
                        authorUid = null,
                        authorName = null
                    )
                    // 塞进列表并重新排序
                    val updatedChapters =
                        (cachedDir.chapters + currentItem).sortedBy { it.chapterNum }
                    val updatedDir = cachedDir.copy(chapters = updatedChapters)

                    // 悄悄更新本地缓存
                    dirMap[cleanName] = updatedDir
                    saveDirectoryMap(dirMap)

                    return@withContext updatedDir
                }

                return@withContext cachedDir
            }

            // 2. 本地完全没有这本漫画的记录，进行初次解析与入库
            val tagId = MangaHtmlParser.findTagIdMobile(mobileHtml)

            val strategy: DirectoryStrategy
            val sourceKey: String
            val initialChapters: List<MangaChapterItem>

            if (tagId != null) {
                // 1. 有 Tag
                strategy = DirectoryStrategy.TAG
                sourceKey = tagId
                initialChapters = emptyList() // 也可以马上提取一下同页链接垫底，但下次按更新会拿全量
            } else {
                // 2. 无 Tag，找同页超链接 (保底那 60%)
                val links = MangaHtmlParser.extractSamePageLinks(mobileHtml)
                if (links.isNotEmpty()) {
                    strategy = DirectoryStrategy.LINKS
                    sourceKey = cleanName
                    initialChapters = links
                } else {
                    // 3. 啥都没有，只有当前这一话
                    strategy = DirectoryStrategy.PENDING_SEARCH
                    sourceKey = cleanName
                    initialChapters = listOf(
                        MangaChapterItem(
                            tid,
                            rawTitle,
                            MangaTitleCleaner.extractChapterNum(rawTitle),
                            currentUrl,
                            null,
                            null
                        )
                    )
                }
            }

            val directory = MangaDirectory(cleanName, strategy, sourceKey, initialChapters)

            // 保存至本地 Map 并覆盖
            dirMap[cleanName] = directory
            saveDirectoryMap(dirMap)

            return@withContext directory
        }
    }

    /**
     * 核心动作 2：用户手动点击“更新目录”按钮
     */
    suspend fun manuallyUpdateDirectory(currentDir: MangaDirectory): Result<MangaDirectory> {
        return withContext(Dispatchers.IO) {
            val newChapters = mutableListOf<MangaChapterItem>()

            try {
                if (currentDir.strategy == DirectoryStrategy.TAG) {
                    // 低开销，直接请求 Tag 页
                    val html = mangaApi.getTagPageHtml(currentDir.sourceKey).string()
                    newChapters.addAll(MangaHtmlParser.parseListHtml(html))
                } else {
                    // 高开销，执行全局 30 秒冷却拦截
                    val now = System.currentTimeMillis()
                    if (now - GlobalData.lastSearchTimestamp < 30_000L) {
                        return@withContext Result.failure(Exception("论坛搜索接口冷却中，请稍候再试"))
                    }
                    GlobalData.lastSearchTimestamp = now

                    val html = mangaApi.searchForum(keyword = currentDir.sourceKey).string()

                    if (MangaHtmlParser.isFloodControlOrError(html)) {
                        return@withContext Result.failure(Exception("论坛服务器繁忙(防灌水限制)，请稍后再试"))
                    }
                    newChapters.addAll(MangaHtmlParser.parseListHtml(html))
                }

                // 合并去重与排序算法
                val mergedChapters = mergeAndSortChapters(currentDir.chapters, newChapters)

                // 使用 copy 生成不可变对象的最新状态
                val updatedDir = currentDir.copy(
                    chapters = mergedChapters,
                    lastUpdateTime = System.currentTimeMillis(),
                    // 这里已经在处理 SEARCHED 的状态变更了
                    strategy = if (currentDir.strategy != DirectoryStrategy.TAG) DirectoryStrategy.SEARCHED else currentDir.strategy
                )

                // 获取最新的 Map，将更新后的对象塞进去保存
                val dirMap = getDirectoryMap()
                dirMap[updatedDir.cleanBookName] = updatedDir
                saveDirectoryMap(dirMap)

                Result.success(updatedDir)

            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 合并算法：以 TID 为唯一键去重，按 chapterNum 升序排列
     */
    private fun mergeAndSortChapters(
        oldList: List<MangaChapterItem>,
        newList: List<MangaChapterItem>
    ): List<MangaChapterItem> {
        val map = LinkedHashMap<String, MangaChapterItem>()
        // 旧的先入 Map
        oldList.forEach { map[it.tid] = it }
        // 新的覆盖旧的（因为新的可能包含更新的发帖人等信息）
        newList.forEach { map[it.tid] = it }

        return map.values.toList().sortedBy { it.chapterNum }
    }
}