package org.shirakawatyu.yamibo.novel.ui.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shirakawatyu.yamibo.novel.bean.DirectoryStrategy
import org.shirakawatyu.yamibo.novel.bean.MangaDirectory
import org.shirakawatyu.yamibo.novel.repository.DirectoryRepository
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner

class MangaDirectoryVM(application: Application) : AndroidViewModel(application) {
    private val logTag = "MangaDirectoryVM"
    private val repo = DirectoryRepository.getInstance(application)

    // 当前正在浏览的漫画目录数据
    var currentDirectory by mutableStateOf<MangaDirectory?>(null)
        private set

    // 是否正在请求网络更新目录
    var isUpdatingDirectory by mutableStateOf(false)
        private set

    // 更新按钮的冷却时间 (秒)
    var directoryCooldown by mutableIntStateOf(0)
        private set

    var showSearchShortcut by mutableStateOf(false)
        private set
    var searchShortcutCountdown by mutableIntStateOf(0)
        private set

    /**
     * 接收 WebView 传来的 HTML 进行静态解析初始化
     */
    fun initDirectoryFromWeb(url: String, html: String, title: String) {
        val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: return
        viewModelScope.launch {
            // 调用我们之前写好的底层解析与降级保底逻辑
            val dir = repo.initDirectoryForThread(tid, url, title, html)
            currentDirectory = dir

            // ==========================================
            // 如果判定为 TAG 策略（低开销）且从未更新过完整目录，直接触发更新
            // ==========================================
            if (dir.strategy == DirectoryStrategy.TAG && dir.lastUpdateTime == 0L) {
                updateMangaDirectory()
            }
        }
    }

    /**
     * 触发目录更新 (连接到 MangaChapterBottomSheet 的更新按钮)
     */
    fun updateMangaDirectory(isForced: Boolean = false) {
        val dir = currentDirectory ?: return
        if (isUpdatingDirectory || directoryCooldown > 0) return

        viewModelScope.launch {
            isUpdatingDirectory = true
            showSearchShortcut = false // 开始更新时关闭窗口

            val result = repo.manuallyUpdateDirectory(dir, forceSearch = isForced)

            result.onSuccess { updateResult ->
                currentDirectory = updateResult.directory

                if (updateResult.searchPerformed) {
                    // 如果执行了搜索（无论是强制还是自动降级），进入 30s 冷却
                    startDirectoryCooldown(30)
                } else {
                    // 如果是 TAG 更新成功，开启 5s 的“全局搜索”窗口期
                    triggerSearchShortcutWindow()
                }
            }.onFailure { error ->
                if (error.message?.contains("搜索冷却") == true) {
                    startDirectoryCooldown(30)
                } else {
                    startDirectoryCooldown(5)
                }
            }
            isUpdatingDirectory = false
        }
    }

    private fun triggerSearchShortcutWindow() {
        viewModelScope.launch {
            showSearchShortcut = true
            searchShortcutCountdown = 5
            while (searchShortcutCountdown > 0) {
                delay(1000)
                searchShortcutCountdown--
            }
            showSearchShortcut = false
        }
    }

    private fun startDirectoryCooldown(seconds: Int) {
        viewModelScope.launch {
            directoryCooldown = seconds
            while (directoryCooldown > 0) {
                delay(1000)
                directoryCooldown--
            }
        }
    }
}