package org.shirakawatyu.yamibo.novel.ui.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.shirakawatyu.yamibo.novel.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义全屏阅读状态栏
 * 显示：时间 + 电量百分比 + 电池图标
 */
@Composable
fun CustomStatusBar(
    modifier: Modifier = Modifier,
    height: Dp,
    backgroundColor: Color,
    contentColor: Color,
    title: String
) {
    val context = LocalContext.current
    var timeString by remember { mutableStateOf("") }
    var batteryLevel by remember {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, iFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        val initialLevel = if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100
        }
        mutableIntStateOf(initialLevel)
    }

    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            timeString = format.format(Date())
            delay(1000)
        }
    }

    DisposableEffect(context) {
        val batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        batteryLevel = (level * 100 / scale.toFloat()).toInt()
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
        onDispose {
            context.unregisterReceiver(batteryReceiver)
        }
    }

    val batteryIconRes = remember(batteryLevel) {
        when {
            batteryLevel >= 90 -> R.drawable.ic_battery_full
            batteryLevel >= 70 -> R.drawable.ic_battery_5
            batteryLevel >= 50 -> R.drawable.ic_battery_4
            batteryLevel >= 35 -> R.drawable.ic_battery_3
            batteryLevel >= 20 -> R.drawable.ic_battery_2
            else -> R.drawable.ic_battery_1
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(backgroundColor)
    ) {
        // 1. 左侧：时间
        Text(
            text = timeString,
            color = contentColor,
            fontSize = 12.sp,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
        )

        // 2. 中间：标题
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 100.dp) // 预留左右空间给时间和电量
        )

        // 3. 右侧：电量 + 图标
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$batteryLevel%",
                color = contentColor,
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.width(4.dp))

            Image(
                painter = painterResource(id = batteryIconRes),
                contentDescription = "Battery",
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}