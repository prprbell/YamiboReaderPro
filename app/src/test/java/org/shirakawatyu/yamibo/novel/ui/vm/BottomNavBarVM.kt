package org.shirakawatyu.yamibo.novel.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.shirakawatyu.yamibo.novel.ui.state.BottomNavBarState

class BottomNavBarVM: ViewModel() {
    private val _uiState = MutableStateFlow(BottomNavBarState())
    val uiState = _uiState.asStateFlow()
    var selectedItem by mutableIntStateOf(0)
        private set
    private val pageList = listOf("FavoritePage", "BBSPage", "MinePage")

    fun changeSelection(index: Int, navController: NavController) {
        selectedItem = index
        navController.navigate(pageList[index])
    }
}