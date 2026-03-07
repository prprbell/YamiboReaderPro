package org.shirakawatyu.yamibo.novel.ui.vm

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * 接收 WebView 传来的 HTML 进行静态解析初始化
     */
    fun initDirectoryFromWeb(url: String, html: String, title: String) {
        val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: return
        viewModelScope.launch {
            // 调用我们之前写好的底层解析与降级保底逻辑
            val dir = repo.initDirectoryForThread(tid, url, title, html)
            currentDirectory = dir
        }
    }

    /**
     * 触发目录更新 (连接到 MangaChapterBottomSheet 的更新按钮)
     */
    fun updateMangaDirectory() {
        val dir = currentDirectory ?: return
        if (isUpdatingDirectory || directoryCooldown > 0) return

        viewModelScope.launch {
            isUpdatingDirectory = true
            val result = repo.manuallyUpdateDirectory(dir)

            result.onSuccess { updatedDir ->
                Log.d(
                    logTag,
                    "Directory updated successfully. Chapters: ${updatedDir.chapters.size}"
                )
                currentDirectory = updatedDir
            }.onFailure { e ->
                Log.e(logTag, "Failed to update directory", e)
            }

            isUpdatingDirectory = false
            startDirectoryCooldown(30)
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