package org.shirakawatyu.yamibo.novel.bean

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class MangaSettings(
    val readMode: Int = 0, // 0: 下滑, 1: 左滑, 2: 右滑
    val isAscending: Boolean = true // 正序/倒序偏好，默认正序
) {
    companion object {
        private const val PREF_NAME = "manga_settings_pref"
        private const val KEY_READ_MODE = "read_mode"
        private const val KEY_IS_ASCENDING = "is_ascending"
        fun getSettings(context: Context): MangaSettings {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return MangaSettings(
                readMode = prefs.getInt(KEY_READ_MODE, 0),
                isAscending = prefs.getBoolean(KEY_IS_ASCENDING, true)
            )
        }

        fun saveReadMode(context: Context, mode: Int) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit { putInt(KEY_READ_MODE, mode) }
        }

        fun saveIsAscending(context: Context, isAscending: Boolean) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_IS_ASCENDING, isAscending) }
        }
    }
}