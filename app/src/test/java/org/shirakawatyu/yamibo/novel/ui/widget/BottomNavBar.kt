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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.BottomNavBarVM

@Composable
fun BottomNavBar(
    navController: NavController,
    navBarVM: BottomNavBarVM = viewModel()
) {
    val uiState by navBarVM.uiState.collectAsState()
    NavigationBar(Modifier.height(50.dp), containerColor = YamiboColors.onSurface) {
        uiState.icons.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = { Icon(item, contentDescription = "") },
                selected = navBarVM.selectedItem == index,
                colors = NavigationBarItemDefaults.colors(indicatorColor = YamiboColors.tertiary),
                onClick = { navBarVM.changeSelection(index, navController) }
            )
        }
    }
}


