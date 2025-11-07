package org.shirakawatyu.yamibo.novel.ui.page

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.constant.RequestConfig
import org.shirakawatyu.yamibo.novel.ui.state.ReaderState
import org.shirakawatyu.yamibo.novel.ui.theme.YellowLightDark
import org.shirakawatyu.yamibo.novel.ui.vm.ReaderVM
import org.shirakawatyu.yamibo.novel.ui.widget.ContentViewer
import org.shirakawatyu.yamibo.novel.ui.widget.PassageWebView
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor
import kotlin.math.roundToInt

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun ReaderPage(
    readerVM: ReaderVM = viewModel(),
    url: String = ""
) {
    val uiState by readerVM.uiState.collectAsState()
    // -1 是因为我们不希望用户滚动到最后一页 "正在加载下一页"
    val pageCount = (uiState.htmlList.size - 1).coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { uiState.htmlList.size })
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SetStatusBarColor(YellowLightDark)

    BoxWithConstraints {
        LaunchedEffect(Unit) {
            readerVM.firstLoad(url, maxHeight, maxWidth)
        }
    }
    if (readerVM.displayWebView) {
        PassageWebView(url = "${RequestConfig.BASE_URL}/${url}&page=${uiState.currentView}") { html, _ ->
            readerVM.loadFinished(html)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { showSettings = true }
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            readerVM.onTransform(pan, zoom)
                        }
                    }
                    .graphicsLayer(
                        scaleX = uiState.scale,
                        scaleY = uiState.scale,
                        translationX = uiState.offset.x,
                        translationY = uiState.offset.y
                    ),
                state = pagerState
            ) { page ->
                ContentViewer(
                    data = uiState.htmlList[page],
                    padding = uiState.padding,
                    lineHeight = uiState.lineHeight,
                    letterSpacing = uiState.letterSpacing,
                    fontSize = uiState.fontSize,
                    currentPage = pagerState.currentPage + 1, // +1 使页码从1开始
                    pageCount = pageCount // 使用计算好的总页数
                )

                SideEffect {
                    readerVM.onPageChange(pagerState, scope)
                }
            }

            if (showSettings) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                readerVM.saveSettings()
                                showSettings = false
                            }
                        )
                )

                ReaderSettingsBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    uiState = uiState,
                    pageCount = pageCount, // 传入修正后的页数
                    currentPage = pagerState.currentPage,
                    onSetView = { readerVM.onSetView(it) },
                    onSetPage = {
                        // 确保在协程中调用
                        scope.launch {
                            pagerState.animateScrollToPage(it)
                        }
                    },
                    onSetFontSize = { readerVM.onSetFontSize(it) },
                    onSetLineHeight = { readerVM.onSetLineHeight(it) },
                    onSetPadding = { readerVM.onSetPadding(it) }
                )
            }
        }
    }
}

@Composable
fun ReaderSettingsBar(
    modifier: Modifier = Modifier,
    uiState: ReaderState,
    pageCount: Int,
    currentPage: Int,
    onSetView: (view: Int) -> Unit,
    onSetPage: (page: Int) -> Unit,
    onSetFontSize: (fontSize: TextUnit) -> Unit,
    onSetLineHeight: (lineHeight: TextUnit) -> Unit,
    onSetPadding: (padding: Dp) -> Unit
) {
    // 状态：用于控制显示主菜单还是二级“间距”菜单
    var showSpacingMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .clickable(indication = null, // 阻止点击穿透
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        // 根据状态显示不同的菜单
        if (showSpacingMenu) {
            SpacingSettingsMenu(
                uiState = uiState,
                onSetLineHeight = onSetLineHeight,
                onSetPadding = onSetPadding,
                onBack = { showSpacingMenu = false } // 返回按钮
            )
        } else {
            MainSettingsMenu(
                uiState = uiState,
                pageCount = pageCount,
                currentPage = currentPage,
                onSetView = onSetView,
                onSetPage = onSetPage,
                onSetFontSize = onSetFontSize,
                onShowSpacingMenu = { showSpacingMenu = true } // 进入二级菜单
            )
        }
    }
}

/**
 * 主设置菜单
 */
@Composable
private fun MainSettingsMenu(
    uiState: ReaderState,
    pageCount: Int,
    currentPage: Int,
    onSetView: (view: Int) -> Unit,
    onSetPage: (page: Int) -> Unit,
    onSetFontSize: (fontSize: TextUnit) -> Unit,
    onShowSpacingMenu: () -> Unit
) {
    // 用于平滑拖动 Slider，仅在拖动结束后才触发翻页
    var sliderPage by remember(currentPage) {
        mutableFloatStateOf(currentPage.toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- 1. 网页翻页 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { onSetView(uiState.currentView - 1) },
                enabled = uiState.currentView > 1 // 第一页时禁用
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一页(网页)")
            }
            Text(
                text = "网页: ${uiState.currentView}",
                style = MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = { onSetView(uiState.currentView + 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一页(网页)")
            }
        }

        // --- 2. 阅读器翻页 Slider ---
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "页数: ${currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = sliderPage,
                onValueChange = { sliderPage = it }, // 拖动时只更新本地状态
                onValueChangeFinished = { // 拖动结束时才调用 VM
                    onSetPage(sliderPage.roundToInt())
                },
                valueRange = 0f..(pageCount - 1).toFloat().coerceAtLeast(0f),
                steps = (pageCount - 2).coerceAtLeast(0) // 步数
            )
        }


        // --- 3. 字体和间距 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：字体调节
            SettingAdjuster(
                label = "字体",
                value = "${uiState.fontSize.value.roundToInt()}",
                onDecrease = {
                    val newSize = (uiState.fontSize.value - 1f).coerceAtLeast(12f)
                    onSetFontSize(newSize.sp)
                },
                onIncrease = {
                    val newSize = (uiState.fontSize.value + 1f).coerceAtMost(40f)
                    onSetFontSize(newSize.sp)
                }
            )

            // 右侧：间距按钮
            Button(
                onClick = onShowSpacingMenu,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text("间距")
            }
        }
    }
}

/**
 * 二级菜单 - 间距调节 (行高, 页距)
 */
@Composable
private fun SpacingSettingsMenu(
    uiState: ReaderState,
    onSetLineHeight: (lineHeight: TextUnit) -> Unit,
    onSetPadding: (padding: Dp) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // --- 1. 标题和返回按钮 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                text = "间距调节",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. 行高调节 ---
        // (范围动态基于字体大小，确保不重叠)
        val minLineHeight = (uiState.fontSize.value * 1.6f)
        val maxLineHeight = (uiState.fontSize.value * 3.0f)
        SettingsSlider(
            label = "行距",
            value = uiState.lineHeight.value,
            valueRange = minLineHeight..maxLineHeight,
            steps = 9, // 10个档位
            onValueChange = { onSetLineHeight(it.sp) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- 3. 页距 (左右边距) 调节 ---
        SettingsSlider(
            label = "页距 (左右)",
            value = uiState.padding.value,
            valueRange = 0f..40f, // 0dp 到 40dp
            steps = 9, // 10个档位
            onValueChange = { onSetPadding(it.dp) }
        )
    }
}


/**
 * 通用组件：带标签的 Slider
 */
@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${value.roundToInt()}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = TextAlign.End
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}


/**
 * 通用组件：带 + - 按钮的调节器 (来自您之前的代码)
 */
@Composable
private fun SettingAdjuster(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp) // 减小按钮间距
        ) {
            IconButton(onClick = onDecrease) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_remove),
                    contentDescription = "Decrease $label"
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(min = 24.dp), // 缩小最小宽度
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrease) {
                Icon(Icons.Default.Add, contentDescription = "Increase $label")
            }
        }
    }
}