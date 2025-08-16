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
    private val accessOrder = ConcurrentHashMap<K, AtomicLong>()
    private val mutex = Mutex()
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

    suspend fun get(key: K): V? = mutex.withLock {
        val entry = cache[key]
        if (entry != null && !entry.isExpired(expireAfterMs)) {
            accessOrder[key] = AtomicLong(accessCounter.incrementAndGet())
            entry.value
        } else {
            cache.remove(key)
            accessOrder.remove(key)
            null
        }
    }

    suspend fun put(key: K, value: V) = mutex.withLock {
        if (cache.size >= maxSize) {
            evictLRU()
        }
        cache[key] = CacheEntry(value)
        accessOrder[key] = AtomicLong(accessCounter.incrementAndGet())
    }

    private fun evictLRU() {
        val lruKey = accessOrder.entries.minByOrNull { it.value.get() }?.key
        if (lruKey != null) {
            cache.remove(lruKey)
            accessOrder.remove(lruKey)
        }
    }

    suspend fun clear() = mutex.withLock {
        cache.clear()
        accessOrder.clear()
    }

    suspend fun size(): Int = mutex.withLock {
        cache.size
    }
}