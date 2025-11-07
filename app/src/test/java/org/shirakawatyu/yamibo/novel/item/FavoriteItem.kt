package org.shirakawatyu.yamibo.novel.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors

@Preview
@Composable
fun FavoriteItem(
    title: String = "一周一次买下同班同学一周一次买下同班同学一周一次买下同班同学",
    lastView: Int = 1,
    lastPage: Int = 2,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = YamiboColors.tertiary)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(15.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    modifier = Modifier.padding(0.dp, 5.dp),
                    fontSize = 16.sp,
                    color = Color.Black,
                    maxLines = 2,
                    text = title
                )
                Text(color = Color.Black, fontSize = 12.sp, text = "上次读到第${lastPage}页, 对应网页第${lastView}页")
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "", tint = Color.Black)
        }
    }
}