package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BbsSkeletonScreen(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton_transition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )

    val baseHeaderColor = Color(0xFF551200)
    val headerBg = baseHeaderColor.copy(alpha = alpha + 0.8f)

    val baseYellowishColor = Color(0xFFD4C8B0)
    val skeletonColor = baseYellowishColor.copy(alpha = alpha)

    val baseDarkRedColor = Color(0xFF9E6565)
    val sectionHeaderBg = baseDarkRedColor.copy(alpha = alpha * 0.8f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(headerBg)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.width(100.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).background(skeletonColor))
            Box(Modifier.align(Alignment.CenterEnd).size(24.dp).clip(CircleShape).background(skeletonColor))
        }
        // 轮播图
        Box(
            modifier = Modifier
                .padding(horizontal = 15.dp, vertical = 10.dp)
                .fillMaxWidth()
                .aspectRatio(2.81f)
                .clip(RoundedCornerShape(4.dp))
                .background(skeletonColor.copy(alpha = alpha * 0.8f))
        )

        BbsSkeletonSectionHeader("庙堂", sectionHeaderBg, skeletonColor)
        repeat(2) {
            BbsSkeletonForumItem(skeletonColor, alpha)
        }

        Spacer(modifier = Modifier.height(10.dp))
        BbsSkeletonSectionHeader("江湖", sectionHeaderBg, skeletonColor)
        repeat(12) {
            BbsSkeletonForumItem(skeletonColor, alpha)
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}
@Composable
private fun BbsSkeletonSectionHeader(title: String, bgColor: Color, fgColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 5.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 模拟标题文字占位
            Box(Modifier.width(60.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(fgColor))
        }
    }
}
@Composable
private fun BbsSkeletonForumItem(skeletonColor: Color, alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧 48x48 图标
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(skeletonColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 版块名称
                Box(Modifier.height(18.dp).width(70.dp).clip(RoundedCornerShape(2.dp)).background(skeletonColor))
                Spacer(Modifier.width(8.dp))
                // 今日更新数
                Box(Modifier.height(12.dp).width(35.dp).clip(RoundedCornerShape(2.dp)).background(skeletonColor.copy(alpha = alpha * 0.5f)))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 版块介绍
            Box(Modifier.height(14.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(2.dp)).background(skeletonColor.copy(alpha = alpha * 0.7f)))
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 15.dp),
        thickness = 0.8.dp,
        color = Color.Gray.copy(alpha = 0.1f)
    )
}