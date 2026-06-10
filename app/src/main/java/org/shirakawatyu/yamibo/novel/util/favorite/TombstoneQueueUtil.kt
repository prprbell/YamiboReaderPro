package org.shirakawatyu.yamibo.novel.util.favorite

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.Favorite
import org.shirakawatyu.yamibo.novel.global.GlobalData

/**
 * 网络离线删除场景的"墓碑队列"
 *
 * 每个 entry 格式为 "url|favId"。对外暴露两组读接口：
 * - [getPendingEntries] 返回原始 entry，供重试删除使用；
 * - [getPendingUrls] 只返回 url，供刷新时过滤已删除项。
 */
object TombstoneQueueUtil {
    private val pendingDeleteKey = stringSetPreferencesKey("yamibo_pending_delete_queue")
    private var memoryTombstoneQueue: MutableSet<String> = mutableSetOf()
    private val tombstoneMutex = Mutex()

    private fun entryUrl(entry: String): String = entry.substringBefore("|")

    /**
     * 初始化加载墓碑队列
     */
    suspend fun initQueue() {
        tombstoneMutex.withLock {
            val savedSet = GlobalData.dataStore?.data?.map { it[pendingDeleteKey] }?.firstOrNull()
            memoryTombstoneQueue = savedSet?.toMutableSet() ?: mutableSetOf()
        }
    }

    /**
     * 获取原始 entry 集合（"url|favId" 格式），用于重试删除。
     */
    suspend fun getPendingEntries(): Set<String> =
        tombstoneMutex.withLock { memoryTombstoneQueue.toSet() }

    /**
     * 获取纯 url 集合，用于刷新时过滤已删除项。
     */
    suspend fun getPendingUrls(): Set<String> =
        tombstoneMutex.withLock { memoryTombstoneQueue.map { entryUrl(it) }.toSet() }

    /**
     * 追加到墓碑队列
     */
    suspend fun addItems(favorites: List<Favorite>) {
        tombstoneMutex.withLock {
            val newEntries = favorites.mapNotNull {
                if (it.favId != null) "${it.url}|${it.favId}" else null
            }
            memoryTombstoneQueue.addAll(newEntries)
            val snapshot = memoryTombstoneQueue.toSet()
            GlobalData.dataStore?.edit { pref ->
                pref[pendingDeleteKey] = snapshot
            }
        }
    }

    /**
     * 按纯 url 移除墓碑（匹配 entry 的 url 前缀）。
     */
    suspend fun removeUrls(urls: Set<String>) {
        tombstoneMutex.withLock {
            memoryTombstoneQueue.removeAll { entryUrl(it) in urls }
            val snapshot = memoryTombstoneQueue.toSet()
            GlobalData.dataStore?.edit { pref ->
                pref[pendingDeleteKey] = snapshot
            }
        }
    }

    /**
     * 按完整 entry 精确移除墓碑。
     */
    suspend fun removeEntries(entries: Set<String>) {
        tombstoneMutex.withLock {
            memoryTombstoneQueue.removeAll(entries)
            val snapshot = memoryTombstoneQueue.toSet()
            GlobalData.dataStore?.edit { pref ->
                pref[pendingDeleteKey] = snapshot
            }
        }
    }
}
