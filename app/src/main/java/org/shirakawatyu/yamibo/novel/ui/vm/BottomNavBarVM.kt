package org.shirakawatyu.yamibo.novel.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.ui.state.BottomNavBarState

class BottomNavBarVM : ViewModel() {
    private val _uiState = MutableStateFlow(BottomNavBarState())
    val uiState = _uiState.asStateFlow()
    var selectedItem by mutableIntStateOf(0)
        private set
    private val pageList = listOf("FavoritePage", "BBSPage", "MinePage")
    var isNavigating by mutableStateOf(false)
        private set

    fun changeSelection(index: Int, navController: NavController) {
        if (isNavigating) return
        isNavigating = true
        selectedItem = index
        navController.navigate(pageList[index]) {
            launchSingleTop = true
            restoreState = true
        }
        viewModelScope.launch {
            delay(300) // 300毫秒的防抖窗口
            isNavigating = false
        }
    }

}