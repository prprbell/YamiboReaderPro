package org.shirakawatyu.yamibo.novel.ui.vm

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.ui.state.BottomNavBarState
import org.shirakawatyu.yamibo.novel.util.SettingsUtil

class BottomNavBarVM : ViewModel() {
    private val _uiState = MutableStateFlow(BottomNavBarState())
    val uiState = _uiState.asStateFlow()
    private val pageList = listOf("FavoritePage", "BBSPage", "MinePage")
    var isBbsAtRoot by mutableStateOf(true)
    var isMineAtRoot by mutableStateOf(true)
    var isNavigating by mutableStateOf(false)
        private set
    var showBottomNavBar by mutableStateOf(true)
        private set
    private val _refreshEvent = MutableSharedFlow<String>()
    val refreshEvent = _refreshEvent.asSharedFlow()
    private val _goHomeEvent = MutableSharedFlow<String>()
    val goHomeEvent = _goHomeEvent.asSharedFlow()
    private val _darkModeEvent = MutableSharedFlow<String>(replay = 1)
    val darkModeEvent = _darkModeEvent.asSharedFlow()
    private val _checkUpdateEvent = MutableSharedFlow<Unit>()
    val checkUpdateEvent = _checkUpdateEvent.asSharedFlow()

    fun triggerRefresh(route: String) {
        viewModelScope.launch {
            _refreshEvent.emit(route)
        }
    }

    fun triggerGoHome(route: String) {
        viewModelScope.launch {
            _goHomeEvent.emit(route)
        }
    }

    fun triggerDarkMode(route: String) {
        viewModelScope.launch {
            val newValue = !GlobalData.isDarkMode.value
            GlobalData.isDarkMode.value = newValue
            SettingsUtil.saveDarkMode(newValue)
            _darkModeEvent.emit(route)
        }
    }

    fun applyTheme(route: String, themeId: Int) {
        viewModelScope.launch {
            if (themeId == -1) {
                GlobalData.isDarkMode.value = false
                SettingsUtil.saveDarkMode(false)
            } else {
                GlobalData.isDarkMode.value = true
                GlobalData.darkModeTheme.value = themeId
                SettingsUtil.saveDarkMode(true)
                SettingsUtil.saveDarkModeTheme(themeId)
            }
            _darkModeEvent.emit(route)
        }
    }

    fun triggerCheckUpdate() {
        viewModelScope.launch {
            _checkUpdateEvent.emit(Unit)
        }
    }

    fun changeSelection(index: Int, navController: NavController) {
        if (isNavigating) return
        if (index < 0 || index >= pageList.size) {
            Log.e("BottomNavBarVM", "Invalid navigation index $index")
            return
        }
        isNavigating = true
        navController.navigate(pageList[index]) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        viewModelScope.launch {
            delay(300)
            isNavigating = false
        }
    }

    fun setBottomNavBarVisibility(visible: Boolean) {
        showBottomNavBar = visible
    }
}