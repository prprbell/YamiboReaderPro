package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.runtime.Composable
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.widget.ComposableWebView
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor

@Composable
fun MinePage() {
    SetStatusBarColor(YamiboColors.primary)
    ComposableWebView(url = "https://bbs.yamibo.com/home.php?mod=space&do=profile&mycenter=1&mobile=2")
}