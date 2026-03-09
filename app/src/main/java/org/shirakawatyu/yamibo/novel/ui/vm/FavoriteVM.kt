package org.shirakawatyu.yamibo.novel.ui.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.YamiboRetrofit
import org.shirakawatyu.yamibo.novel.network.FavoriteApi
import org.shirakawatyu.yamibo.novel.ui.state.FavoriteState
import org.shirakawatyu.yamibo.novel.util.CookieUtil
import org.shirakawatyu.yamibo.novel.util.FavoriteUtil
import org.shirakawatyu.yamibo.novel.util.LocalCacheUtil
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder

class FavoriteVM(private val applicationContext: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoriteState())
    val uiState = _uiState.asStateFlow()

    private val logTag = "FavoriteVM"
    private var allFavorites: List<Favorite> = listOf()

    var currentCategory: Int = -1
        private set

    // 本地缓存工具
    private val localCache by lazy { LocalCacheUtil.getInstance(applicationContext) }

    init {
        viewModelScope.launch {
            FavoriteUtil.getFavoriteFlow().collect { fullList ->
                allFavorites = fullList
                updateUiList()
                refreshCacheInfo(localCache.index.value)
            }
        }

        viewModelScope.launch {
            localCache.index.collect { index ->
                if (allFavorites.isNotEmpty()) {
                    refreshCacheInfo(index)
                }
            }
        }
    }

    fun setCategory(category: Int) {
        currentCategory = category
        updateUiList()
    }

    private fun updateUiList() {
        val currentState = _uiState.value
        val baseList = if (currentState.isInManageMode) {
            allFavorites
        } else {
            allFavorites.filter { !it.isHidden }
        }

        val filteredList = if (currentCategory == -1) {
            baseList
        } else {
            baseList.filter { it.type == currentCategory }
        }

        _uiState.value = currentState.copy(favoriteList = filteredList)
    }

    fun refreshList(showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }
        CookieUtil.getCookie {
            val favoriteApi = YamiboRetrofit.getInstance().create(FavoriteApi::class.java)
            favoriteApi.getFavoritePage().enqueue(object : Callback<ResponseBody> {
                override fun onResponse(
                    call: Call<ResponseBody>,
                    response: Response<ResponseBody>
                ) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val respHTML = response.body()?.string()
                        if (respHTML != null) {
                            val parse = Jsoup.parse(respHTML)
                            val favList = parse.getElementsByClass("sclist")
                            val objList = ArrayList<Favorite>()
                            favList.forEach { li ->
                                val title = li.text()
                                val url = li.child(1).attribute("href").value
                                objList.add(Favorite(title, url))
                            }
                            FavoriteUtil.addFavorite(objList) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                                }
                            }
                        } else {
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(isRefreshing = false)
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    t.printStackTrace()
                    viewModelScope.launch(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isRefreshing = false)
                    }
                }
            })
        }
    }

    fun clickHandler(favorite: Favorite, navController: NavController) {
        val urlEncoded = URLEncoder.encode(favorite.url, "utf-8")
        when (favorite.type) {
            1 -> navController.navigate("ReaderPage/$urlEncoded")
            2 -> {
                val targetUrl = favorite.lastMangaUrl ?: favorite.url
                Log.d("MangaProgress", "最终跳转到: $targetUrl")
                val encodedTarget = URLEncoder.encode(targetUrl, "utf-8")
                val encodedOriginal = URLEncoder.encode(favorite.url, "utf-8")
                navController.navigate("MangaWebPage/$encodedTarget/$encodedOriginal")
            }

            3 -> navController.navigate("OtherWebPage/$urlEncoded")
            else -> navController.navigate("ProbingPage/$urlEncoded") // 0 或 未知
        }
    }

    fun updateMangaProgress(favoriteUrl: String, chapterUrl: String, chapterTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                if (fav.url == favoriteUrl) {
                    fav.copy(lastMangaUrl = chapterUrl, lastChapter = chapterTitle)
                } else {
                    fav
                }
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) {
                updateUiList()
            }
        }
    }

    // 3. 拖拽排序
    fun moveFavorite(from: Int, to: Int) {
        if (_uiState.value.isInManageMode) return

        val currentUiList = _uiState.value.favoriteList.toMutableList()
        if (from < 0 || from >= currentUiList.size || to < 0 || to >= currentUiList.size || from == to) return

        val item = currentUiList.removeAt(from)
        currentUiList.add(to, item)

        _uiState.value = _uiState.value.copy(favoriteList = currentUiList.toList())

        viewModelScope.launch(Dispatchers.IO) {
            val categoryUrls = currentUiList.map { it.url }.toSet()
            val newQueue = java.util.LinkedList(currentUiList)

            val newListToSave = allFavorites.map { fav ->
                if (categoryUrls.contains(fav.url)) {
                    newQueue.poll() ?: fav
                } else {
                    fav
                }
            }

            allFavorites = newListToSave
            FavoriteUtil.saveFavoriteOrder(newListToSave)
        }
    }

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(
            isInManageMode = !_uiState.value.isInManageMode,
            selectedItems = emptySet()
        )
        updateUiList()
    }

    fun toggleItemSelection(url: String) {
        if (!_uiState.value.isInManageMode) return

        val newSelections = _uiState.value.selectedItems.toMutableSet()
        if (newSelections.contains(url)) newSelections.remove(url)
        else newSelections.add(url)

        _uiState.value = _uiState.value.copy(selectedItems = newSelections)
    }

    fun hideSelectedItems() {
        val itemsToHide = _uiState.value.selectedItems
        if (itemsToHide.isEmpty()) return
        viewModelScope.launch {
            FavoriteUtil.updateHiddenStatus(itemsToHide, true) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedItems = emptySet())
                }
            }
        }
    }

    fun unhideSelectedItems() {
        val itemsToUnhide = _uiState.value.selectedItems
        if (itemsToUnhide.isEmpty()) return
        viewModelScope.launch {
            FavoriteUtil.updateHiddenStatus(itemsToUnhide, false) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedItems = emptySet())
                }
            }
        }
    }

    private fun refreshCacheInfo(index: Map<String, LocalCacheUtil.CacheIndex>) {
        try {
            val cacheInfoMap = mutableMapOf<String, CacheInfo>()
            index.forEach { (url, novelCache) ->
                if (novelCache.pages.isNotEmpty()) {
                    val totalPages = novelCache.pages.size
                    val totalSize = novelCache.pages.values.sumOf { it.fileSize }
                    val pagesWithImages = novelCache.pages.values.count { it.hasImages }

                    cacheInfoMap[url] = CacheInfo(url, totalPages, totalSize, pagesWithImages)
                }
            }
            _uiState.value = _uiState.value.copy(cacheInfoMap = cacheInfoMap)
        } catch (e: Exception) {
            Log.e(logTag, "从内存索引刷新缓存信息失败", e)
            _uiState.value = _uiState.value.copy(cacheInfoMap = emptyMap())
        }
    }

    // ==================== 缓存/书签/目录等管理功能保持不变 ====================

    data class CacheInfo(
        val url: String,
        val totalPages: Int,
        val totalSize: Long,
        val pagesWithImages: Int
    )

    fun refreshCacheInfo() = refreshCacheInfo(localCache.index.value)

    fun getCacheInfo(callback: (Map<String, CacheInfo>) -> Unit) {
        refreshCacheInfo(localCache.index.value)
        callback(_uiState.value.cacheInfoMap)
    }

    fun deleteFavoriteCache(url: String) {
        viewModelScope.launch {
            try {
                localCache.deleteNovel(url)
            } catch (e: Exception) {
                Log.e(logTag, "删除 $url 的缓存失败", e)
            }
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            try {
                localCache.clearAllCache()
            } catch (e: Exception) {
                Log.e(logTag, "清除所有缓存失败", e)
            }
        }
    }

    fun clearBookmark(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                if (fav.url == url) fav.copy(
                    lastPage = 0,
                    lastView = 1,
                    lastChapter = null,
                    lastMangaUrl = null
                ) else fav
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) { updateUiList() }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = allFavorites.map { fav ->
                fav.copy(lastPage = 0, lastView = 1, lastChapter = null, lastMangaUrl = null)
            }
            allFavorites = updated
            FavoriteUtil.saveFavoriteOrder(updated)
            viewModelScope.launch(Dispatchers.Main) { updateUiList() }
        }
    }

    fun getDirectoryList(callback: (List<org.shirakawatyu.yamibo.novel.bean.MangaDirectory>) -> Unit) {
        viewModelScope.launch {
            val repo = org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            )
            viewModelScope.launch(Dispatchers.Main) { callback(repo.getAllDirectories()) }
        }
    }

    fun deleteDirectory(cleanName: String, callback: () -> Unit) {
        viewModelScope.launch {
            org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            ).deleteDirectory(cleanName)
            viewModelScope.launch(Dispatchers.Main) { callback() }
        }
    }

    fun clearAllDirectories(callback: () -> Unit) {
        viewModelScope.launch {
            org.shirakawatyu.yamibo.novel.repository.DirectoryRepository.getInstance(
                applicationContext
            ).clearAllDirectories()
            viewModelScope.launch(Dispatchers.Main) { callback() }
        }
    }
}