package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM

@Composable
fun BottomNavBar(
    navController: NavController,
    currentRoute: String?,
    navBarVM: BottomNavBarVM
) {
    if (!navBarVM.showBottomNavBar) return
    val uiState by navBarVM.uiState.collectAsState()
    val webProgress by GlobalData.webProgress.collectAsState()
    val pageList = listOf("FavoritePage", "BBSPage", "MinePage")

    val animatedProgress by animateFloatAsState(
        targetValue = webProgress.toFloat() / 100f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "WebProgressAnimation"
    )

    Box(modifier = Modifier.fillMaxWidth()) {

        NavigationBar(
            Modifier.height(50.dp),
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = YamiboColors.onSurface
        ) {
            uiState.icons.forEachIndexed { index, item ->
                val targetRoute = pageList[index]
                val isSelected = currentRoute == targetRoute
                NavigationBarItem(
                    icon = { Icon(item, contentDescription = "") },
                    selected = isSelected,
                    colors = NavigationBarItemDefaults.colors(indicatorColor = YamiboColors.tertiary),
                    onClick = {
                        if (currentRoute == targetRoute) return@NavigationBarItem
                        navBarVM.changeSelection(index, navController)
                    }
                )
            }
        }

        // 全局进度条
        AnimatedVisibility(
            visible = webProgress in 1..99 && (currentRoute == "BBSPage" || currentRoute == "MinePage"),
            enter = fadeIn(tween(200)) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(200)
            ),
            exit = fadeOut(tween(300)) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(300)
            ),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = YamiboColors.primary,
                trackColor = YamiboColors.primary.copy(alpha = 0.1f),
                strokeCap = StrokeCap.Round
            )
        }
    }
}