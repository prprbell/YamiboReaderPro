package org.shirakawatyu.yamibo.novel.global

import android.util.DisplayMetrics
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.util.CookieUtil

class GlobalData {

    companion object {
        val webViewClient = YamiboWebViewClient()
        val webChromeClient = WebChromeClient()
        var dataStore: DataStore<Preferences>? = null
        var displayMetrics: DisplayMetrics? = null
        var currentCookie: String = ""
        var isAppInitialized by mutableStateOf(false)
        val cookieFlow: Flow<String> by lazy {
            CookieUtil.getCookieFlow()
        }
    }
}