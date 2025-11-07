package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.widget.ComposableWebView
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor

@Preview
@Composable
fun BBSPage() {
    SetStatusBarColor(YamiboColors.primary)
    ComposableWebView(url = "https://bbs.yamibo.com/forum.php")
}