package com.lsd.wififrankenstein.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AdvancedCache<K, V> private constructor(
    private val maxSize: Int,
    private val expireAfterMs: Long = 300000L
) {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val accessOrder = ConcurrentHashMap<K, Long>()
    private val orderIndex = java.util.TreeMap<Long, K>()
    private val lock = Mutex()
    private var accessCounter = AtomicLong(0)

    companion object {
        fun <K, V> create(): AdvancedCache<K, V> {
            val maxSize = if (PerformanceManager.shouldUseAdvancedCaching()) {
                PerformanceManager.getOptimalCacheSize()
            } else {
                1000
            }
            return AdvancedCache(maxSize)
        }
    }

    private data class CacheEntry<V>(
        val value: V,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(expireAfterMs: Long): Boolean {
            return System.currentTimeMillis() - timestamp > expireAfterMs
        }
    }

    suspend fun get(key: K): V? {
        val entry = cache[key] ?: return null
        if (entry.isExpired(expireAfterMs)) {
            lock.withLock { removeEntryLocked(key) }
            return null
        }
        lock.withLock {
            val newOrder = accessCounter.incrementAndGet()
            val oldOrder = accessOrder.put(key, newOrder)
            if (oldOrder != null) {
                orderIndex.remove(oldOrder)
            }
            orderIndex[newOrder] = key
        }
        return entry.value
    }

    suspend fun put(key: K, value: V) {
        lock.withLock {
            removeEntryLocked(key)
            if (cache.size >= maxSize) {
                evictLRU()
            }
            cache[key] = CacheEntry(value)
            val order = accessCounter.incrementAndGet()
            accessOrder[key] = order
            orderIndex[order] = key
        }
    }

    private fun removeEntryLocked(key: K) {
        val oldOrder = accessOrder.remove(key)
        if (oldOrder != null) {
            orderIndex.remove(oldOrder)
        }
        cache.remove(key)
    }

    private fun evictLRU() {
        val oldest = orderIndex.firstEntry() ?: return
        val key = oldest.value
        orderIndex.remove(oldest.key)
        accessOrder.remove(key)
        cache.remove(key)
    }

    suspend fun clear() {
        lock.withLock {
            cache.clear()
            accessOrder.clear()
            orderIndex.clear()
        }
    }

    suspend fun size(): Int {
        return cache.size
    }
}
