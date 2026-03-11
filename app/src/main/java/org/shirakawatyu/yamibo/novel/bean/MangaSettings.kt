package org.shirakawatyu.yamibo.novel.bean

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class MangaSettings(
    val readMode: Int = 0 // 0: 下滑 (垂直), 1: 左滑 (LTR 横屏), 2: 右滑 (RTL 横屏)
) {
    companion object {
        private const val PREF_NAME = "manga_settings_pref"
        private const val KEY_READ_MODE = "read_mode"

        fun getSettings(context: Context): MangaSettings {
            val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return MangaSettings(
                readMode = prefs.getInt(KEY_READ_MODE, 0)
            )
        }

        fun saveReadMode(context: Context, mode: Int) {
            val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit { putInt(KEY_READ_MODE, mode) }
        }
    }
}