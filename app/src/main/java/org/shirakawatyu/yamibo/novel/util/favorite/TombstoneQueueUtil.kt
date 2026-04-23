package org.shirakawatyu.yamibo.novel.util.favorite

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.global.GlobalData

/**
 * 网络离线删除场景的“墓碑队列”
 */
object TombstoneQueueUtil {
    private val pendingDeleteKey = stringSetPreferencesKey("yamibo_pending_delete_queue")
    
    private var memoryTombstoneQueue: MutableSet<String> = mutableSetOf()
    private val tombstoneMutex = Mutex()

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
     * 获取内存墓碑副本
     */
    fun getPendingUrls(): Set<String> {
        return memoryTombstoneQueue.toSet()
    }

    /**
     * 追加到墓碑队列
     */
    suspend fun addUrls(urls: Set<String>) {
        tombstoneMutex.withLock {
            memoryTombstoneQueue.addAll(urls)
            val snapshot = memoryTombstoneQueue.toSet()
            GlobalData.dataStore?.edit { pref ->
                pref[pendingDeleteKey] = snapshot
            }
        }
    }

    /**
     * 从墓碑队列中移除
     */
    suspend fun removeUrls(urls: Set<String>) {
        tombstoneMutex.withLock {
            memoryTombstoneQueue.removeAll(urls)
            val snapshot = memoryTombstoneQueue.toSet()
            GlobalData.dataStore?.edit { pref ->
                pref[pendingDeleteKey] = snapshot
            }
        }
    }
}