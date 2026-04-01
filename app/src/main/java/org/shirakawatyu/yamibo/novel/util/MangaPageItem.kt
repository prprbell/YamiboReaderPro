package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM

data class MangaPageItem(
    val uniqueId: String,       // 唯一标识 (TID_localIndex)，用于Compose列表锚点复用
    val globalIndex: Int,       // 在大列表中的绝对索引
    val tid: String,            // 归属章节的TID
    val chapterUrl: String,     // 归属章节的URL
    val chapterTitle: String,   // 章节名
    val imageUrl: String,       // 图片URL
    val localIndex: Int,        // 在该章节内的相对页码
    val chapterTotalPages: Int  // 该章节总页数
)

data class LoadedChapter(
    val tid: String,
    val url: String,
    val title: String,
    val pages: List<MangaPageItem>
)

class MangaReaderManager(
    private val context: Context,
    private val mangaDirVM: MangaDirectoryVM,
    private val scope: CoroutineScope,
    private val fallbackNavigate: (String) -> Unit
) {
    // 供UI直接绑定的展平的大列表
    var flatPages by mutableStateOf<List<MangaPageItem>>(emptyList())
        private set
    var isLoadingPrev by mutableStateOf(false)
        private set
    var isLoadingNext by mutableStateOf(false)
        private set

    // 用于标记是否为用户主动点击导致的强制跳转（需要展示全局遮罩）
    var isManualJumping by mutableStateOf(false)
        private set

    // 保留20话
    private val loadedChapters = mutableListOf<LoadedChapter>()
    private val maxLoadedChapters = 20

    fun initFirstChapter(tid: String, url: String, title: String, urls: List<String>) {
        val pages = urls.mapIndexed { index, imgUrl ->
            MangaPageItem(
                uniqueId = "${tid}_$index",
                globalIndex = index,
                tid = tid,
                chapterUrl = url,
                chapterTitle = title,
                imageUrl = imgUrl,
                localIndex = index,
                chapterTotalPages = urls.size
            )
        }
        loadedChapters.clear()
        loadedChapters.add(LoadedChapter(tid, url, title, pages))
        updateFlatPages()
    }

    private fun updateFlatPages() {
        var index = 0
        val newList = mutableListOf<MangaPageItem>()
        for (chapter in loadedChapters) {
            for (page in chapter.pages) {
                newList.add(page.copy(globalIndex = index++))
            }
        }
        flatPages = newList
    }

    fun loadPrevious(isManualJump: Boolean = false, onLoaded: (() -> Unit)? = null) {
        if (isLoadingPrev || loadedChapters.isEmpty()) return
        val firstChapter = loadedChapters.first()
        val dir = mangaDirVM.currentDirectory ?: return
        val chapters = dir.chapters
        val currentIndex = chapters.indexOfFirst { it.tid == firstChapter.tid }
        if (currentIndex <= 0) return // 已经是第一话了

        val prevChapterInfo = chapters[currentIndex - 1]

        if (isManualJump) isManualJumping = true else isLoadingPrev = true

        scope.launch {
            MangaProber().probeUrl(
                context = context,
                url = prevChapterInfo.url,
                onSuccess = { urls, title, _ ->
                    val newPages = urls.mapIndexed { i, imgUrl ->
                        MangaPageItem(
                            uniqueId = "${prevChapterInfo.tid}_$i",
                            globalIndex = 0, // 会在 updateFlatPages 被重写
                            tid = prevChapterInfo.tid,
                            chapterUrl = prevChapterInfo.url,
                            chapterTitle = title,
                            imageUrl = imgUrl,
                            localIndex = i,
                            chapterTotalPages = urls.size
                        )
                    }
                    val newChapter = LoadedChapter(prevChapterInfo.tid, prevChapterInfo.url, title, newPages)

                    // 头插法
                    loadedChapters.add(0, newChapter)
                    // 超过3话，移除尾部（最下方/最后方）的章节
                    if (loadedChapters.size > maxLoadedChapters) {
                        loadedChapters.removeLast()
                    }
                    updateFlatPages()

                    if (isManualJump) isManualJumping = false else isLoadingPrev = false
                    onLoaded?.invoke()
                },
                onFallback = {
                    if (isManualJump) isManualJumping = false else isLoadingPrev = false
                    fallbackNavigate(prevChapterInfo.url) // 解析失败走传统页面跳转
                }
            )
        }
    }

    fun loadNext(isManualJump: Boolean = false, onLoaded: (() -> Unit)? = null) {
        if (isLoadingNext || loadedChapters.isEmpty()) return
        val lastChapter = loadedChapters.last()
        val dir = mangaDirVM.currentDirectory ?: return
        val chapters = dir.chapters
        val currentIndex = chapters.indexOfFirst { it.tid == lastChapter.tid }
        if (currentIndex == -1 || currentIndex == chapters.size - 1) return // 已经是最新话了

        val nextChapterInfo = chapters[currentIndex + 1]

        if (isManualJump) isManualJumping = true else isLoadingNext = true

        scope.launch {
            MangaProber().probeUrl(
                context = context,
                url = nextChapterInfo.url,
                onSuccess = { urls, title, _ ->
                    val newPages = urls.mapIndexed { i, imgUrl ->
                        MangaPageItem(
                            uniqueId = "${nextChapterInfo.tid}_$i",
                            globalIndex = 0,
                            tid = nextChapterInfo.tid,
                            chapterUrl = nextChapterInfo.url,
                            chapterTitle = title,
                            imageUrl = imgUrl,
                            localIndex = i,
                            chapterTotalPages = urls.size
                        )
                    }
                    val newChapter = LoadedChapter(nextChapterInfo.tid, nextChapterInfo.url, title, newPages)

                    // 尾插法
                    loadedChapters.add(newChapter)
                    // 超过3话，移除头部（最上方/最前方）的章节
                    if (loadedChapters.size > maxLoadedChapters) {
                        loadedChapters.removeFirst()
                    }
                    updateFlatPages()

                    if (isManualJump) isManualJumping = false else isLoadingNext = false
                    onLoaded?.invoke()
                },
                onFallback = {
                    if (isManualJump) isManualJumping = false else isLoadingNext = false
                    fallbackNavigate(nextChapterInfo.url)
                }
            )
        }
    }

    // 处理无缝跳转逻辑
    fun jumpToChapter(targetUrl: String, onScrollTo: (Int) -> Unit) {
        val targetTid = org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner.extractTidFromUrl(targetUrl) ?: return
        val globalIndex = flatPages.indexOfFirst { it.tid == targetTid }

        if (globalIndex != -1) {
            // 目标话数已在内存中，直接滚动过去
            onScrollTo(globalIndex)
        } else {
            val dir = mangaDirVM.currentDirectory ?: return fallbackNavigate(targetUrl)
            val chapters = dir.chapters

            // 如果目标是相邻章节但不在内存中（可能由于被回收），尝试探测后跳转
            // 如果跨度较大（比如直接通过目录跳了10话），则走传统的 navigate 销毁当前页重建
            val currentFirstTid = loadedChapters.firstOrNull()?.tid
            val currentLastTid = loadedChapters.lastOrNull()?.tid

            val targetIndex = chapters.indexOfFirst { it.tid == targetTid }
            val firstIndex = chapters.indexOfFirst { it.tid == currentFirstTid }
            val lastIndex = chapters.indexOfFirst { it.tid == currentLastTid }

            if (targetIndex == firstIndex - 1) {
                // 手动跳转模式（带全局遮罩）
                loadPrevious(isManualJump = true) {
                    val newGlobalIdx = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIdx != -1) onScrollTo(newGlobalIdx)
                }
            } else if (targetIndex == lastIndex + 1) {
                // 手动跳转模式（带全局遮罩）
                loadNext(isManualJump = true) {
                    val newGlobalIdx = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIdx != -1) onScrollTo(newGlobalIdx)
                }
            } else {
                fallbackNavigate(targetUrl)
            }
        }
    }
}