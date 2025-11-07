package org.shirakawatyu.yamibo.novel.ui.state

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavBarState (
    var icons: List<ImageVector> = listOf(Icons.Filled.Favorite, Icons.Filled.Home, Icons.Filled.Person)
)