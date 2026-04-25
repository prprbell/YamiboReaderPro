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
 * Native 漫画阅读章节管理器
 */
class MangaReaderManager(
    private val context: Context,
    private val mangaDirVM: MangaDirectoryVM,
    private val scope: CoroutineScope,
    private val pipelineOwnerKey: String,
    private val fallbackNavigate: (String) -> Unit
) {
    var flatPages by mutableStateOf<List<MangaPageItem>>(emptyList())
        private set

    var isLoadingPrev by mutableStateOf(false)
        private set

    var isLoadingNext by mutableStateOf(false)
        private set

    var isManualJumping by mutableStateOf(false)
        private set

    private val loadedChapters = mutableListOf<LoadedChapter>()
    private val maxLoadedChapters = 10

    companion object {
        private const val COLD_PREFETCH_EDGE_SKIP = 3
    }

    fun initFirstChapter(
        tid: String,
        url: String,
        title: String,
        urls: List<String>
    ) {
        val normalizedUrls = normalizeUrls(urls)

        val pages = normalizedUrls.mapIndexed { index, imgUrl ->
            MangaPageItem(
                uniqueId = "${tid}_$index",
                globalIndex = index,
                tid = tid,
                chapterUrl = url,
                chapterTitle = title,
                imageUrl = imgUrl,
                localIndex = index,
                chapterTotalPages = normalizedUrls.size
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

    fun loadPrevious(
        isManualJump: Boolean = false,
        onLoaded: (() -> Unit)? = null
    ) {
        if (isAnyLoading() || loadedChapters.isEmpty()) return

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
                    onSuccess = onSuccess@{ urls, title, _ ->
                        val normalizedUrls = normalizeUrls(urls)

                        if (normalizedUrls.isEmpty()) {
                            setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
                            fallbackNavigate(prevChapterInfo.url)
                            return@onSuccess
                        }

                        val newPages = normalizedUrls.mapIndexed { i, imgUrl ->
                            MangaPageItem(
                                uniqueId = "${prevChapterInfo.tid}_$i",
                                globalIndex = 0,
                                tid = prevChapterInfo.tid,
                                chapterUrl = prevChapterInfo.url,
                                chapterTitle = title,
                                imageUrl = imgUrl,
                                localIndex = i,
                                chapterTotalPages = normalizedUrls.size
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

                        prefetchPreviousChapterCold(normalizedUrls)

                        onLoaded?.invoke()
                        setLoadingState(isPrevious = true, isManualJump = isManualJump, loading = false)
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
        if (isAnyLoading() || loadedChapters.isEmpty()) return

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
                    onSuccess = onSuccess@{ urls, title, _ ->
                        val normalizedUrls = normalizeUrls(urls)

                        if (normalizedUrls.isEmpty()) {
                            setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
                            fallbackNavigate(nextChapterInfo.url)
                            return@onSuccess
                        }

                        val newPages = normalizedUrls.mapIndexed { i, imgUrl ->
                            MangaPageItem(
                                uniqueId = "${nextChapterInfo.tid}_$i",
                                globalIndex = 0,
                                tid = nextChapterInfo.tid,
                                chapterUrl = nextChapterInfo.url,
                                chapterTitle = title,
                                imageUrl = imgUrl,
                                localIndex = i,
                                chapterTotalPages = normalizedUrls.size
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

                        prefetchNextChapterCold(normalizedUrls)

                        onLoaded?.invoke()
                        setLoadingState(isPrevious = false, isManualJump = isManualJump, loading = false)
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

    fun jumpToChapter(
        targetUrl: String,
        onScrollTo: (Int) -> Unit
    ) {
        if (isAnyLoading()) return

        val targetTid = MangaTitleCleaner.extractTidFromUrl(targetUrl)
            ?: return fallbackNavigate(targetUrl)

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

        if (targetIndex == -1 || firstIndex == -1 || lastIndex == -1) {
            fallbackNavigate(targetUrl)
            return
        }

        when (targetIndex) {
            firstIndex - 1 -> {
                loadPrevious(isManualJump = true) {
                    val newGlobalIndex = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIndex != -1) {
                        onScrollTo(newGlobalIndex)
                    }
                }
            }

            lastIndex + 1 -> {
                loadNext(isManualJump = true) {
                    val newGlobalIndex = flatPages.indexOfFirst { it.tid == targetTid }
                    if (newGlobalIndex != -1) {
                        onScrollTo(newGlobalIndex)
                    }
                }
            }

            else -> {
                fallbackNavigate(targetUrl)
            }
        }
    }

    private fun prefetchNextChapterCold(urls: List<String>) {
        val edgeUrls = urls.take(COLD_PREFETCH_EDGE_SKIP)
        if (edgeUrls.isNotEmpty()) {
            MangaImagePipeline.prefetchChapterEdge(
                context = context.applicationContext,
                ownerKey = pipelineOwnerKey,
                urls = edgeUrls
            )
        }

        val coldUrls = urls.drop(COLD_PREFETCH_EDGE_SKIP)
        if (coldUrls.isEmpty()) return

        MangaImagePipeline.coldPrefetchChapter(
            context = context.applicationContext,
            urls = coldUrls,
            parentOwnerKey = pipelineOwnerKey
        )
    }

    private fun prefetchPreviousChapterCold(urls: List<String>) {
        val edgeUrls = urls.takeLast(COLD_PREFETCH_EDGE_SKIP).asReversed()
        if (edgeUrls.isNotEmpty()) {
            MangaImagePipeline.prefetchChapterEdge(
                context = context.applicationContext,
                ownerKey = pipelineOwnerKey,
                urls = edgeUrls
            )
        }

        val coldUrls = urls.dropLast(COLD_PREFETCH_EDGE_SKIP).asReversed()
        if (coldUrls.isEmpty()) return

        MangaImagePipeline.coldPrefetchChapter(
            context = context.applicationContext,
            urls = coldUrls,
            parentOwnerKey = pipelineOwnerKey
        )
    }

    private fun normalizeUrls(urls: List<String>): List<String> {
        return urls
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun isAnyLoading(): Boolean {
        return isLoadingPrev || isLoadingNext || isManualJumping
    }

    private fun setLoadingState(
        isPrevious: Boolean,
        isManualJump: Boolean,
        loading: Boolean
    ) {
        if (isPrevious) {
            isLoadingPrev = loading
        } else {
            isLoadingNext = loading
        }

        if (isManualJump) {
            isManualJumping = loading
        }
    }
}