package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors

// 弃用
@Preview
@Composable
fun IndeterminateCircularIndicator() {
    if (1 == 0) {
        Surface(
            Modifier
                .size(50.dp)
                .padding(5.dp), shape = CircleShape, shadowElevation = 10.dp
        ) {
            CircularProgressIndicator(
                color = YamiboColors.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 6.dp
            )
        }
    }
}