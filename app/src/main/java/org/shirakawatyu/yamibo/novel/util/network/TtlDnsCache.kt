package org.shirakawatyu.yamibo.novel.util.network

import okhttp3.Dns
import org.shirakawatyu.yamibo.novel.global.GlobalData
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TtlDnsCache(
    private val delegate: Dns,
    private val ttlMillis: Long = TimeUnit.HOURS.toMillis(24)
) : Dns {

    private val cache = ConcurrentHashMap<String, CachedRecord>()

    private data class CachedRecord(
        val addresses: List<InetAddress>,
        val timestamp: Long
    )

    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DnsRefresh").apply { isDaemon = true }
    }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]

        if (cached != null) {
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < ttlMillis) {
                if (age > ttlMillis * 4 / 5) {
                    triggerAsyncRefresh(hostname)
                }
                return cached.addresses
            }
        }

        return try {
            val freshAddresses = delegate.lookup(hostname)

            val sortedAddresses = if (GlobalData.Companion.isCustomDnsEnabled.value) {
                freshAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }
            } else {
                freshAddresses
            }

            if (sortedAddresses.isNotEmpty()) {
                cache[hostname] = CachedRecord(sortedAddresses, System.currentTimeMillis())
            }
            sortedAddresses
        } catch (e: Exception) {
            cached?.addresses ?: throw e
        }
    }

    private fun triggerAsyncRefresh(hostname: String) {
        if (!refreshing.add(hostname)) return
        refreshExecutor.submit {
            try {
                val freshAddresses = delegate.lookup(hostname)
                if (freshAddresses.isNotEmpty()) {
                    val sortedAddresses = if (GlobalData.Companion.isCustomDnsEnabled.value) {
                        freshAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }
                    } else {
                        freshAddresses
                    }
                    if (sortedAddresses.isNotEmpty()) {
                        cache[hostname] = CachedRecord(sortedAddresses, System.currentTimeMillis())
                    }
                }
            } catch (_: Exception) {
            } finally {
                refreshing.remove(hostname)
            }
        }
    }

    fun invalidate(hostname: String) {
        cache.remove(hostname)
    }

    fun clearCache() {
        cache.clear()
    }
}
