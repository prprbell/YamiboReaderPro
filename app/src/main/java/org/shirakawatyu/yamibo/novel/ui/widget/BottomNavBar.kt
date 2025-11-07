package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM

@Composable
fun BottomNavBar(
    navController: NavController,
    currentRoute: String?,
    navBarVM: BottomNavBarVM
) {
    val uiState by navBarVM.uiState.collectAsState()
    val pageList = listOf("FavoritePage", "BBSPage", "MinePage")
    NavigationBar(Modifier.height(50.dp), containerColor = YamiboColors.onSurface) {
        uiState.icons.forEachIndexed { index, item ->
            val targetRoute = pageList[index]
            val isSelected = navBarVM.selectedItem == index
            NavigationBarItem(
                icon = { Icon(item, contentDescription = "") },
                selected = isSelected,
                colors = NavigationBarItemDefaults.colors(indicatorColor = YamiboColors.tertiary),
                onClick = {
                    // 如果当前页面和目标页面相同，则不执行任何操作
                    if (currentRoute == targetRoute) {
                        return@NavigationBarItem
                    }
                    navBarVM.changeSelection(index, navController)
                }
            )
        }
    }
}