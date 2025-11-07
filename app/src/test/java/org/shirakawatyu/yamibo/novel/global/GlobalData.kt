package org.shirakawatyu.yamibo.novel.global

import android.util.DisplayMetrics
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.shirakawatyu.yamibo.novel.module.YamiboWebViewClient
import org.shirakawatyu.yamibo.novel.util.CookieUtil

class GlobalData {

    init {
        CookieUtil.getCookie {
            cookie = it
        }
    }

    companion object {
        val webViewClient = YamiboWebViewClient()
        val webChromeClient = WebChromeClient()
        var dataStore: DataStore<Preferences>? = null
        var displayMetrics: DisplayMetrics? = null
        var loading by mutableStateOf(false)
        var cookie: String = ""
    }
}