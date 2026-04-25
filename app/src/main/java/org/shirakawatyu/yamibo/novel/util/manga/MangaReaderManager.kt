package org.shirakawatyu.yamibo.novel.util.manga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.item.LoadedChapter
import org.shirakawatyu.yamibo.novel.item.MangaPageItem
import org.shirakawatyu.yamibo.novel.ui.vm.MangaDirectoryVM

/**
 * Native 漫画阅读章节管理器。
 *
 * 职责边界：
 * 1. 维护已加载章节 loadedChapters。
 * 2. 维护供 UI 直接绑定的 flatPages。
 * 3. 处理上一话/下一话/章节跳转。
 * 4. 章节探测成功后，把整章图片交给 MangaImagePipeline 做冷预取。
 *
 * 注意：
 * 本类不再直接接触 Coil / ImageRequest / CachePolicy。
 * 所有图片下载、缓存、in-flight 去重、预加载策略统一由 MangaImagePipeline 管理。
 */
class MangaReaderManager(
    private val context: Context,
    private val mangaDirVM: MangaDirectoryVM,
    private val scope: CoroutineScope,
    private val fallbackNavigate: (String) -> Unit
) {
    var flatPages by mutableStateOf<List<MangaPageItem>>(emptyList())
        private set

    var isLoadingPrev by mutableStateOf(false)
        private set

    var isLoadingNext by mutableStateOf(false)
        private set

    /**
     * 用户主动点击章节跳转时展示全局遮罩。
     */
    var isManualJumping by mutableStateOf(false)
        private set

    private val loadedChapters = mutableListOf<LoadedChapter>()
    private val maxLoadedChapters = 10

    fun initFirstChapter(
        tid: String,
        url: String,
        title: String,
        urls: List<String>
    ) {
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

        // 初始章节也交给统一管线做一次 handoff/cold prefetch。
        // 如果 BBSPage/MangaProber 已经提交过，Pipeline 会通过 in-flight / cache 去重。
        MangaImagePipeline.coldPrefetchChapter(
            context = context.applicationContext,
            urls = urls
        )
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

    fun loadPrevious(
        isManualJump: Boolean = false,
        onLoaded: (() -> Unit)? = null
    ) {
        if (isLoadingPrev || loadedChapters.isEmpty()) return

        val firstChapter = loadedChapters.first()
        val dir = mangaDirVM.currentDirectory ?: return
        val chapters = dir.chapters
        val currentIndex = chapters.indexOfFirst { it.tid == firstChapter.tid }
        if (currentIndex <= 0) return

        val prevChapterInfo = chapters[currentIndex - 1]

        setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = true)

        scope.launch {
            try {
                MangaProber().probeUrl(
                    context = context,
                    url = prevChapterInfo.url,
                    onSuccess = { urls, title, _ ->
                        val newPages = urls.mapIndexed { i, imgUrl ->
                            MangaPageItem(
                                uniqueId = "${prevChapterInfo.tid}_$i",
                                globalIndex = 0,
                                tid = prevChapterInfo.tid,
                                chapterUrl = prevChapterInfo.url,
                                chapterTitle = title,
                                imageUrl = imgUrl,
                                localIndex = i,
                                chapterTotalPages = urls.size
                            )
                        }

                        val newChapter = LoadedChapter(
                            tid = prevChapterInfo.tid,
                            url = prevChapterInfo.url,
                            title = title,
                            pages = newPages
                        )

                        loadedChapters.add(0, newChapter)
                        if (loadedChapters.size > maxLoadedChapters) {
                            loadedChapters.removeAt(loadedChapters.lastIndex)
                        }
                        updateFlatPages()

                        MangaImagePipeline.coldPrefetchChapter(
                            context = context.applicationContext,
                            urls = urls
                        )

                        setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
                        onLoaded?.invoke()
                    },
                    onFallback = {
                        setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
                        fallbackNavigate(prevChapterInfo.url)
                    }
                )
            } catch (e: CancellationException) {
                setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
                throw e
            } catch (_: Throwable) {
                setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
                fallbackNavigate(prevChapterInfo.url)
            }
        }
    }

    fun loadNext(
        isManualJump: Boolean = false,
        onLoaded: (() -> Unit)? = null
    ) {
        if (isLoadingNext || loadedChapters.isEmpty()) return

        val lastChapter = loadedChapters.last()
        val dir = mangaDirVM.currentDirectory ?: return
        val chapters = dir.chapters
        val currentIndex = chapters.indexOfFirst { it.tid == lastChapter.tid }
        if (currentIndex == -1 || currentIndex == chapters.size - 1) return

        val nextChapterInfo = chapters[currentIndex + 1]

        setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = true)

        scope.launch {
            try {
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

                        val newChapter = LoadedChapter(
                            tid = nextChapterInfo.tid,
                            url = nextChapterInfo.url,
                            title = title,
                            pages = newPages
                        )

                        loadedChapters.add(newChapter)
                        if (loadedChapters.size > maxLoadedChapters) {
                            loadedChapters.removeAt(0)
                        }
                        updateFlatPages()

                        MangaImagePipeline.coldPrefetchChapter(
                            context = context.applicationContext,
                            urls = urls
                        )

                        setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
                        onLoaded?.invoke()
                    },
                    onFallback = {
                        setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
                        fallbackNavigate(nextChapterInfo.url)
                    }
                )
            } catch (e: CancellationException) {
                setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
                throw e
            } catch (_: Throwable) {
                setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
                fallbackNavigate(nextChapterInfo.url)
            }
        }
    }

    /**
     * 章节无缝跳转逻辑。
     *
     * 目标章节已在 loadedChapters 中：直接滚动。
     * 目标章节刚好是当前窗口前/后一话：加载相邻章节后滚动。
     * 其他情况：回退到传统跳转，让页面重新探测。
     */
    fun jumpToChapter(
        targetUrl: String,
        onScrollTo: (Int) -> Unit
    ) {
        val targetTid = MangaTitleCleaner.extractTidFromUrl(targetUrl) ?: return
        val globalIndex = flatPages.indexOfFirst { it.tid == targetTid }

        if (globalIndex != -1) {
            onScrollTo(globalIndex)
            return
        }

        val dir = mangaDirVM.currentDirectory ?: return fallbackNavigate(targetUrl)
        val chapters = dir.chapters

        val currentFirstTid = loadedChapters.firstOrNull()?.tid
        val currentLastTid = loadedChapters.lastOrNull()?.tid

        val targetIndex = chapters.indexOfFirst { it.tid == targetTid }
        val firstIndex = chapters.indexOfFirst { it.tid == currentFirstTid }
        val lastIndex = chapters.indexOfFirst { it.tid == currentLastTid }

        when (targetIndex) {
            firstIndex - 1 -> {
                loadPrevious(isManualJump = true) {
                    val newGlobalIndex = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIndex != -1) onScrollTo(newGlobalIndex)
                }
            }

            lastIndex + 1 -> {
                loadNext(isManualJump = true) {
                    val newGlobalIndex = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIndex != -1) onScrollTo(newGlobalIndex)
                }
            }

            else -> {
                fallbackNavigate(targetUrl)
            }
        }
    }

    private fun setLoadingState(
        isPrevious: Boolean,
        isManualJump: Boolean,
        loading: Boolean
    ) {
        if (isManualJump) {
            isManualJumping = loading
        } else if (isPrevious) {
            isLoadingPrev = loading
        } else {
            isLoadingNext = loading
        }
    }
}
