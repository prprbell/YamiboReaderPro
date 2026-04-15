package org.shirakawatyu.yamibo.novel.util

import okhttp3.Dns
import org.shirakawatyu.yamibo.novel.global.GlobalData
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


class TtlDnsCache(
    private val delegate: Dns,
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(10)
) : Dns {

    private val cache = ConcurrentHashMap<String, CachedRecord>()

    private data class CachedRecord(
        val addresses: List<InetAddress>,
        val timestamp: Long
    ) {
        fun isExpired(ttl: Long): Boolean {
            return System.currentTimeMillis() - timestamp > ttl
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]

        if (cached != null && !cached.isExpired(ttlMillis)) {
            return cached.addresses
        }

        return try {
            val freshAddresses = delegate.lookup(hostname)

            val sortedAddresses = if (GlobalData.isCustomDnsEnabled.value) {
                freshAddresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
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

    fun clearCache() {
        cache.clear()
    }
}