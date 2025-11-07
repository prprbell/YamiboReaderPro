package org.shirakawatyu.yamibo.novel.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.shirakawatyu.yamibo.novel.item.FavoriteItem
import org.shirakawatyu.yamibo.novel.ui.theme.YamiboColors
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM
import org.shirakawatyu.yamibo.novel.ui.widget.TopBar
import org.shirakawatyu.yamibo.novel.util.ComposeUtil.Companion.SetStatusBarColor


@Composable
fun FavoritePage(
    favoriteVM: FavoriteVM = viewModel(),
    navController: NavController
) {
    val uiState by favoriteVM.uiState.collectAsState()

    SetStatusBarColor(YamiboColors.onSurface)

    Column {
        TopBar(title = "收藏") {
            Button(onClick = { favoriteVM.refreshList() }) {
                Icon(Icons.Filled.Refresh, "")
            }
        }
        LazyColumn(Modifier.padding(0.dp, 3.dp)) {
            uiState.favoriteList.forEach {
                item {
                    FavoriteItem(it.title, it.lastView, it.lastPage) {
                        favoriteVM.clickHandler(it.url, navController)
                    }
                }
            }
        }
    }
}