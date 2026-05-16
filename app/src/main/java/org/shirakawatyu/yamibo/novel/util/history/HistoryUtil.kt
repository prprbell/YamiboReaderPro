package org.shirakawatyu.yamibo.novel.util.history

import androidx.datastore.preferences.core.stringPreferencesKey
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shirakawatyu.yamibo.novel.bean.HistoryEntry
import org.shirakawatyu.yamibo.novel.global.GlobalData
import org.shirakawatyu.yamibo.novel.util.DataStoreUtil
import org.shirakawatyu.yamibo.novel.util.favorite.FavoriteUtil
import kotlin.coroutines.resume

object HistoryUtil {
    private val key = stringPreferencesKey("yamibo_history")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val writeMutex = Mutex()
    private var pendingHistoryMap: LinkedHashMap<String, HistoryEntry>? = null

    fun getHistoryFlow(): Flow<List<HistoryEntry>> {
        val dataStore = GlobalData.dataStore
            ?: throw IllegalStateException("DataStore not initialized")
        return dataStore.data.map { preferences ->
            writeMutex.withLock {
                pendingHistoryMap?.let {
                    return@withLock it.values.toList().sortedByDescending { e -> e.timestamp }
                }

                val jsonString = preferences[key]
                if (jsonString != null) {
                    try {
                        jsonToHashMap(jsonString).values.toList().sortedByDescending { it.timestamp }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    suspend fun addOrUpdateHistory(url: String, title: String, author: String, section: String) {
        val normalizedUrl = FavoriteUtil.normalizeUrl(url)
        val entry = HistoryEntry(
            url = normalizedUrl,
            title = title,
            author = author,
            section = section,
            timestamp = System.currentTimeMillis()
        )

        writeMutex.withLock {
            val map = getHistoryMapSuspend()
            map.remove(normalizedUrl)
            map[normalizedUrl] = entry

            if (map.size > 500) {
                val oldestKey = map.keys.first()
                map.remove(oldestKey)
            }

            pendingHistoryMap = map
            scheduleSave()
        }
    }

    suspend fun clearHistory() {
        writeMutex.withLock {
            pendingHistoryMap = LinkedHashMap()
            suspendCancellableCoroutine { cont ->
                DataStoreUtil.addData(JSON.toJSONString(pendingHistoryMap), key) {
                    cont.resume(Unit)
                }
            }
        }
    }

    suspend fun deleteEntry(url: String) {
        val normalizedUrl = FavoriteUtil.normalizeUrl(url)
        writeMutex.withLock {
            val map = getHistoryMapSuspend()
            map.remove(normalizedUrl)
            pendingHistoryMap = map
            scheduleSave()
        }
    }

    suspend fun batchDelete(urls: List<String>) {
        writeMutex.withLock {
            val map = getHistoryMapSuspend()
            urls.forEach { map.remove(FavoriteUtil.normalizeUrl(it)) }
            pendingHistoryMap = map
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(1500L)
            writeMutex.withLock {
                pendingHistoryMap?.let {
                    DataStoreUtil.addData(JSON.toJSONString(it), key)
                    pendingHistoryMap = null
                }
            }
        }
    }

    private fun jsonToHashMap(text: String): LinkedHashMap<String, HistoryEntry> {
        val map = LinkedHashMap<String, HistoryEntry>()
        try {
            val jsonObject: JSONObject = JSON.parseObject(text)
            jsonObject.values.forEach {
                val obj = it as JSONObject
                val rawUrl = obj.getString("url") ?: ""
                val entry = HistoryEntry(
                    url = rawUrl,
                    title = obj.getString("title") ?: "",
                    author = obj.getString("author") ?: "",
                    section = obj.getString("section") ?: "",
                    timestamp = obj.getLongValue("timestamp")
                )
                map[entry.url] = entry
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private suspend fun getHistoryMapSuspend(): LinkedHashMap<String, HistoryEntry> =
        suspendCancellableCoroutine { cont ->
            pendingHistoryMap?.let {
                cont.resume(LinkedHashMap(it))
                return@suspendCancellableCoroutine
            }

            DataStoreUtil.getData(key, callback = {
                try {
                    val map = jsonToHashMap(it)
                    cont.resume(map)
                } catch (e: Exception) {
                    e.printStackTrace()
                    cont.resume(LinkedHashMap())
                }
            }, onNull = {
                cont.resume(LinkedHashMap())
            })
        }

    fun isThreadUrl(url: String?): Boolean {
        if (url == null) return false
        return (url.contains("mod=viewthread") && url.contains("tid=")) ||
                Regex("thread-(\\d+)-").containsMatchIn(url)
    }
}
