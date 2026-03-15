package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.shirakawatyu.yamibo.novel.R
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors

/**
 * 阅读模式悬浮按钮
 * * @param visible 是否显示按钮
 * @param onClick 点击回调
 * @param modifier 修饰符
 */
@Composable
fun ReaderModeFAB(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = YamiboColors.secondary.copy(alpha = 0.5f),
            contentColor = YamiboColors.primary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            ),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp)
                .size(48.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_book),
                contentDescription = "转换为阅读模式"
            )
        }
    }
}