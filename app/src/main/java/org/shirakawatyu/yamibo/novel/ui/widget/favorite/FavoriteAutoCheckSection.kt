package org.shirakawatyu.yamibo.novel.ui.widget.favorite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM


/** 把"小时"格式化为更易读的文案：24 的整数倍显示为"天"，否则显示"小时"。 */
private fun formatCheckInterval(hours: Int): String =
    if (hours >= 24 && hours % 24 == 0) "${hours / 24} 天" else "$hours 小时"

@Composable
internal fun AutoCheckSection(
    enabled: Boolean,
    intervalHours: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    enabledCount: Int,
    maxCount: Int,
    isCurrentlyEnabled: Boolean
) {
    val lightModeTheme by GlobalData.lightModeTheme.collectAsState()
    // 仅当"本项尚未启用"且"总数已达上限"时禁止新开
    val atCapForNew = !isCurrentlyEnabled && enabledCount >= maxCount
    val intervals = FavoriteVM.AUTO_CHECK_INTERVALS
    var showCustomDialog by remember { mutableStateOf(false) }

    var customUnitDays by remember { mutableStateOf(false) }
    var customNum by remember { mutableStateOf("12") }

    fun normalizeCustomHours(numText: String, days: Boolean): Int? {
        val raw = numText.toIntOrNull() ?: return null
        if (raw <= 0) return null
        return if (days) raw.coerceIn(1, 30) * 24 else raw.coerceIn(1, 720)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动检查更新", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "名额 $enabledCount / $maxCount",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                enabled = !atCapForNew,
                onCheckedChange = onEnabledChange,
                colors = if (lightModeTheme > 0) SwitchDefaults.colors(
                    uncheckedTrackColor = Color(0xFFCBD5E1),
                    uncheckedBorderColor = Color(0xFFCBD5E1)
                ) else SwitchDefaults.colors()
            )
        }

        if (atCapForNew) {
            Text(
                "已达自动检查上限，请先关闭其它项目再开启",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        val sizeSpec = tween<IntSize>(durationMillis = 300, easing = FastOutSlowInEasing)
        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                    expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top),
            exit = fadeOut(tween(300, easing = FastOutSlowInEasing)) +
                    shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "检查间隔",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Text(
                        formatCheckInterval(intervalHours),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        intervals.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(formatCheckInterval(h)) },
                                onClick = {
                                    onIntervalChange(h)
                                    expanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("自定义...") },
                            onClick = {
                                showCustomDialog = true
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    // 每次打开自定义弹窗时，基于当前 intervalHours 初始化输入值
    LaunchedEffect(showCustomDialog) {
        if (showCustomDialog) {
            val init = if (intervalHours !in intervals) intervalHours.coerceAtLeast(1) else 12
            customUnitDays = init >= 24 && init % 24 == 0
            customNum = if (customUnitDays) (init / 24).toString() else init.toString()
        }
    }

    if (showCustomDialog) {
        val normalized = normalizeCustomHours(customNum, customUnitDays)
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = {
                Text("自定义间隔", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 输入框：使用中性灰色，不用主题色
                    OutlinedTextField(
                        value = customNum,
                        onValueChange = { value ->
                            customNum = value.filter { it.isDigit() }.take(3)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.width(86.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64748B),
                            unfocusedBorderColor = Color(0xFF94A3B8),
                            cursorColor = Color(0xFF475569)
                        )
                    )

                    // 单位切换：用 Row + 分隔线，不用胶囊边框
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val unitOptions = listOf(false to "小时", true to "天")
                        unitOptions.forEachIndexed { idx, (isDays, label) ->
                            val selected = customUnitDays == isDays
                            Box(
                                modifier = Modifier
                                    .clickable { customUnitDays = isDays }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) {
                                        Color(0xFF334155)
                                    } else {
                                        Color(0xFF94A3B8)
                                    }
                                )
                            }
                            if (idx < unitOptions.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(16.dp)
                                        .background(Color(0xFFCBD5E1))
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalized != null,
                    onClick = {
                        normalized?.let(onIntervalChange)
                        showCustomDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
