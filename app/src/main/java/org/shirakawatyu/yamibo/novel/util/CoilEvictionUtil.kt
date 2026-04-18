package org.shirakawatyu.yamibo.novel.util

import android.content.Context
import android.util.Log
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

object CoilEvictionUtil {
    private const val TAG = "CoilEvictionUtil"

    // 【1】Coil在 Application 中配置的绝对上限 (400MB)
    private const val MAX_COIL_SIZE = 400L * 1024 * 1024

    // 【2】高水位线（触发线）：涨到 375MB 时才去清理，平时绝对不碰
    private const val HIGH_WATER_MARK = 375L * 1024 * 1024

    // 【3】低水位线（目标线）：清理完后，我们希望缓存掉到 225MB，腾出 150MB 纯净空间
    private const val TARGET_LOW_WATER_MARK = 225L * 1024 * 1024

    // 【4】垃圾文件的大小 = 绝对上限 - 目标低水位 (150MB)
    private const val DUMMY_FILE_SIZE = MAX_COIL_SIZE - TARGET_LOW_WATER_MARK

    private const val DUMMY_KEY = "dummy_garbage_key_for_lru_eviction"

    private val isEvicting = AtomicBoolean(false)

    // 【5】时间冷却锁：防止极端异常下疯狂重试，限定半小时最多只能执行1次清理
    private var lastEvictionTimeMs = 0L
    private const val COOLDOWN_PERIOD_MS = 30 * 60 * 1000L // 半小时

    @OptIn(ExperimentalCoilApi::class)
    fun checkAndTriggerEviction(context: Context) {
        val diskCache = context.imageLoader.diskCache ?: return

        // 1. 没到高水位线，不清理。
        if (diskCache.size < HIGH_WATER_MARK) return

        // 2. 还在冷却期内，不清理。
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEvictionTimeMs < COOLDOWN_PERIOD_MS) return

        // 3. 正在清理中，不清理。
        if (!isEvicting.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            var editor: DiskCache.Editor? = null
            try {
                editor = diskCache.openEditor(DUMMY_KEY)
                if (editor != null) {
                    val fileSystem = diskCache.fileSystem
                    // 写入 150MB 垃圾
                    fileSystem.sink(editor.data).buffer().use { sink ->
                        val chunk = ByteArray(1024 * 1024)
                        val repeatTimes = (DUMMY_FILE_SIZE / chunk.size).toInt()
                        repeat(repeatTimes) {
                            sink.write(chunk)
                        }
                    }
                    editor.commit()
                }

                // 删掉垃圾
                diskCache.remove(DUMMY_KEY)

                // 记录成功清理的时间，开启冷却锁
                lastEvictionTimeMs = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Eviction failed", e)
                editor?.abort()
                diskCache.remove(DUMMY_KEY)
            } finally {
                isEvicting.set(false)
            }
        }
    }
}