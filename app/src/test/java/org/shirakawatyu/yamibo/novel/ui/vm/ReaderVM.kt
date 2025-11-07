package org.shirakawatyu.yamibo.novel.ui.vm

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.Content
import org.shirakawatyu.yamibo.novel.bean.ContentType
import org.shirakawatyu.yamibo.novel.bean.ReaderSettings
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.state.ReaderState
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.HTMLUtil
import org.shirakawatyu.yamibo.novel.util.SettingsUtil
import org.shirakawatyu.yamibo.novel.util.TextUtil
import org.shirakawatyu.yamibo.novel.util.ValueUtil

class ReaderVM : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderState())
    val uiState = _uiState.asStateFlow()

    private var pagerState: PagerState? = null
    private var pageEnd = true
    private var maxHeight = 0.dp
    private var maxWidth = 0.dp
    private var initialized = false
    private val logTag = "ReaderVM"
    private var compositionScope: CoroutineScope? = null
    var url by mutableStateOf("")
        private set
    var displayWebView by mutableStateOf(false)
        private set

    // 新增一个列表来存储未分页的原始数据
    private val rawContentList = ArrayList<Content>()

    init {
        Log.i(logTag, "VM created.")
    }

    fun firstLoad(initUrl: String, initHeight: Dp, initWidth: Dp) {
        url = initUrl
        maxWidth = initWidth
        maxHeight = initHeight

        val applySettingsAndLoad = { settings: ReaderSettings? ->
            _uiState.value = _uiState.value.copy(
                fontSize = ValueUtil.pxToSp(settings?.fontSizePx ?: 18f),
                lineHeight = ValueUtil.pxToSp(settings?.lineHeightPx ?: 39f),
                padding = (settings?.paddingDp ?: 16f).dp
            )

            FavoriteUtil.getFavoriteMap { it2 ->
                it2[url]?.let { it1 ->
                    Log.i(logTag, "first: $it1")
                    _uiState.value = _uiState.value.copy(currentView = it1.lastView)
                }
                displayWebView = true
            }
        }

        SettingsUtil.getSettings(
            callback = { settings ->
                applySettingsAndLoad(settings)
            },
            onNull = {
                applySettingsAndLoad(null)
            }
        )
    }

    fun loadFinished(html: String) {
        GlobalData.loading = true
        Thread {
            // 解析HTML并填充rawContentList
            parseHtmlToContent(html)
            // 根据当前设置进行分页
            paginateContent()
            GlobalData.loading = false
            if (!initialized) {
                FavoriteUtil.getFavoriteMap {
                    it[url]?.let { it1 ->
                        CoroutineScope(Dispatchers.Main).launch {
                            while (pagerState == null) {
                                delay(100)
                            }
                            compositionScope?.launch {
                                pagerState?.animateScrollToPage(it1.lastPage)
                                initialized = true
                            }
                        }
                    }
                }
            } else {
                compositionScope?.launch {
                    pagerState?.scrollToPage(0)
                }
            }
            displayWebView = false
        }.start()
    }

    /**
     * 解析原始 HTML，提取文本和图片，填充到 rawContentList 中。
     * 这只在加载新网页时运行一次。
     */
    private fun parseHtmlToContent(html: String) {
        // 每次加载新网页时，清空旧的原始数据
        rawContentList.clear()
        val doc = Jsoup.parse(html)
        doc.getElementsByTag("i").forEach { it.remove() }
        for (node in doc.getElementsByClass("message")) {
            // 提取原始文本，但不分页
            val rawText = HTMLUtil.toText(node.html())
            if (rawText.isNotBlank()) {
                rawContentList.add(Content(rawText, ContentType.TEXT))
            }
            // 提取图片
            for (element in node.getElementsByTag("img")) {
                val src = element.attribute("src").value
                rawContentList.add(Content("${RequestConfig.BASE_URL}/${src}", ContentType.IMG))
            }
        }
    }

    /**
     * 使用 rawContentList 中的原始数据，根据当前的 UI 设置 (字体, 边距等)
     * 来计算分页，并更新 UI state。
     * 这会在加载时运行，并在每次设置更改后运行。
     */
    private fun paginateContent() {
        val passages = ArrayList<Content>()
        val state = _uiState.value // 获取当前设置

        val verticalPadding = 16.dp // (来自ContentViewer.kt)
        val pageNumTextHeight = ValueUtil.spToDp(state.lineHeight)
        val pageNumBottomPadding = 8.dp // (来自ContentViewer.kt)

        // 计算真正可用于显示内容的区域大小
        // 可用高度 = 总高度 - 上边距 - 下边距 - 页码高度 - 页码下边距
        val pageContentHeight =
            maxHeight - verticalPadding - verticalPadding - pageNumTextHeight - pageNumBottomPadding
        // 可用宽度 = 总宽度 - 左页距 - 右页距
        val pageContentWidth = maxWidth - (state.padding * 2)
        for (content in rawContentList) {
            if (content.type == ContentType.TEXT) {
                // 使用当前的state设置和修正后的尺寸来进行分页
                val pagedText = TextUtil.pagingText(
                    content.data,
                    pageContentHeight, // 使用修正后的高度
                    pageContentWidth, // 使用修正后的宽度
                    state.fontSize,
                    state.letterSpacing,
                    state.lineHeight,
                )
                for (t in pagedText) {
                    passages.add(Content(t, ContentType.TEXT))
                }
            } else if (content.type == ContentType.IMG) {
                // 图片直接添加
                passages.add(content)
            }
        }
        passages.add(Content("正在加载下一页", ContentType.TEXT))
        _uiState.value = _uiState.value.copy(htmlList = passages)
    }

    fun onPageChange(curPagerState: PagerState, scope: CoroutineScope) {
        var viewIndex = uiState.value.currentView
        if (pagerState == null) {
            pagerState = curPagerState
        }
        if (compositionScope == null) {
            compositionScope = scope
        }
        if (curPagerState.currentPage == curPagerState.targetPage &&
            curPagerState.currentPage == uiState.value.htmlList.size - 1 &&
            curPagerState.currentPage > 0 && pageEnd
        ) {
            viewIndex += 1
            displayWebView = true
            _uiState.value = _uiState.value.copy(currentView = viewIndex)
            pagerState = curPagerState
            pageEnd = false
        } else {
            pageEnd = true
        }
        saveHistory(curPagerState.targetPage)
        if (curPagerState.settledPage != curPagerState.targetPage && _uiState.value.scale != 1f) {
            _uiState.value = _uiState.value.copy(scale = 1f, offset = Offset(0f, 0f))
        }
    }

    private fun saveHistory(curPage: Int) {
        FavoriteUtil.getFavoriteMap {
            it[url]?.let { it1 ->
                FavoriteUtil.updateFavorite(
                    it1.copy(
                        lastPage = curPage,
                        lastView = uiState.value.currentView
                    )
                )
            }
        }
    }

    fun saveSettings() {
        val state = _uiState.value
        pagerState?.let {
            val settings = ReaderSettings(
                ValueUtil.spToPx(state.fontSize),
                ValueUtil.spToPx(state.lineHeight),
                // 保存padding的float值而不是Dp对象
                state.padding.value
            )
            SettingsUtil.saveSettings(settings)
            // 新增:在保存后立即重新分页 (显示加载动画)
            GlobalData.loading = true
            Thread {
                paginateContent()
                GlobalData.loading = false
            }.start()
        }
    }

    fun onTransform(pan: Offset, zoom: Float) {
        val scale = (_uiState.value.scale * zoom).coerceIn(0.5f, 3f)
        val offset = if (scale == 1f) Offset(0f, 0f) else _uiState.value.offset + pan
        _uiState.value = _uiState.value.copy(scale = scale, offset = offset)
    }

    fun onSetView(view: Int) {
        _uiState.value = _uiState.value.copy(currentView = view)
        displayWebView = true
    }

    fun onSetPage(page: Int) {
        compositionScope?.launch {
            pagerState?.animateScrollToPage(page)
        }
    }

    fun onSetFontSize(fontSize: TextUnit) {
        // 1. 定义一个最小行高比例
        val newMinLineHeight = (fontSize.value * 1.6f).sp
        val currentLineHeight = _uiState.value.lineHeight

        // 2. 检查当前行高是否会小于新的最小行高
        if (currentLineHeight < newMinLineHeight) {
            // 如果是，则在设置新字体大小时，也强制拉高行高
            _uiState.value = _uiState.value.copy(
                fontSize = fontSize,
                lineHeight = newMinLineHeight // 自动调整行高
            )
        } else {
            // 否则，只更新字体大小
            _uiState.value = _uiState.value.copy(fontSize = fontSize)
        }
    }

    fun onSetLineHeight(lineHeight: TextUnit) {
        // 1. 确保用户设置的行高，不会小于当前字体大小对应的最小行高
        val currentFontSizeValue = _uiState.value.fontSize.value
        val newMinLineHeightValue = currentFontSizeValue * 1.6f
        // 2. 限制新行高
        val coercedLineHeightValue = lineHeight.value.coerceIn(
            minimumValue = newMinLineHeightValue,
            maximumValue = 100.0f
        )
        _uiState.value = _uiState.value.copy(
            lineHeight = coercedLineHeightValue.sp
        )
    }

    fun onSetPadding(padding: Dp) {
        _uiState.value = _uiState.value.copy(padding = padding)
    }
}