package org.shirakawatyu.yamibo.novel.ui.vm

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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder

class FavoriteVM : ViewModel() {
    private val _uiState = MutableStateFlow(FavoriteState())
    val uiState = _uiState.asStateFlow()

    private val logTag = "FavoriteVM"

    init {
        Log.i(logTag, "VM创建")
        FavoriteUtil.getFavorite {
            _uiState.value = FavoriteState(it)
        }
    }

    fun loadFavorites() {
        viewModelScope.launch {
            FavoriteUtil.getFavorite {
                _uiState.value = FavoriteState(it)
            }
        }
    }

    //TODO: 改成手动添加链接、长按删除记录(?)
    fun refreshList(showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }
        CookieUtil.getCookie {
            Log.i(logTag, it)
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
                            // 遍历解析出的收藏条目，提取标题和链接构造Favorite对象
                            favList.forEach { li ->
                                val title = li.text()
                                val url = li.child(1).attribute("href").value
                                Log.i(logTag, url)
                                objList.add(Favorite(title, url))
                            }
                            // 将新的收藏列表保存至本地并更新UI
                            FavoriteUtil.addFavorite(objList) { filteredList ->
                                viewModelScope.launch(Dispatchers.Main) {
                                    _uiState.value =
                                        FavoriteState(filteredList, isRefreshing = false)
                                }
                            }
                        } else {
                            // 出错时停止加载
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

    fun clickHandler(url: String, navController: NavController) {
        val urlEncoded = URLEncoder.encode(url, "utf-8")
        navController.navigate("ReaderPage/$urlEncoded")
    }

    //拖拽排序功能
    fun moveFavorite(from: Int, to: Int) {
        val currentList = _uiState.value.favoriteList
        if (from < 0 || from >= currentList.size || to < 0 || to >= currentList.size || from == to) {
            return // 无效的移动
        }
        val mutableList = currentList.toMutableList()
        // 移动项目
        val item = mutableList.removeAt(from)
        mutableList.add(to, item)

        // 立即更新UI状态
        _uiState.value = _uiState.value.copy(favoriteList = mutableList.toList())

        // 在后台保存顺序
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteUtil.saveFavoriteOrder(mutableList)
        }
    }
}