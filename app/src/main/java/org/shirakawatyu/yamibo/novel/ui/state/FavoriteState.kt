package org.shirakawatyu.yamibo.novel.ui.state

import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM

data class FavoriteState(
    var favoriteList: List<Favorite> = listOf(),
    var isRefreshing: Boolean = false,
    var isInManageMode: Boolean = false,
    var selectedItems: Set<String> = emptySet(),
    var cacheInfoMap: Map<String, FavoriteVM.CacheInfo> = emptyMap()
)