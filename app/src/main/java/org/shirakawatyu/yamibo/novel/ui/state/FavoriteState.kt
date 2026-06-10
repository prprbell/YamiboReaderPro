package org.shirakawatyu.yamibo.novel.ui.state

import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.bean.MangaUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.NovelUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.bean.OtherUpdateCheckProfile
import org.shirakawatyu.yamibo.novel.ui.vm.FavoriteVM

data class FavoriteState(
    var favoriteList: List<Favorite> = listOf(),
    var isRefreshing: Boolean = false,
    var isInManageMode: Boolean = false,
    var selectedItems: Set<String> = emptySet(),
    var cacheInfoMap: Map<String, FavoriteVM.CacheInfo> = emptyMap(),
    var updateCheckNovels: List<NovelUpdateCheckProfile> = listOf(),
    var updateCheckMangas: List<MangaUpdateCheckProfile> = listOf(),
    var updateCheckOthers: List<OtherUpdateCheckProfile> = listOf(),
    var checkingUpdateUrls: Set<String> = emptySet(),
    var probingTypeUrls: Set<String> = emptySet()
)
