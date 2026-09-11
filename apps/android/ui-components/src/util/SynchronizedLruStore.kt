package com.lomo.ui.util

import androidx.collection.LruCache
import java.util.LinkedHashMap

class SynchronizedLruStore<K : Any, V : Any>(
    maxEntries: Int,
) {
    private val cache =
        object : LruCache<K, V>(maxEntries) {}

    @Synchronized
    fun get(key: K): V? = cache[key]

    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        cache.put(key, value)
    }

    @Synchronized
    fun remove(key: K): V? = cache.remove(key)

    @Synchronized
    fun snapshot(): LinkedHashMap<K, V> = LinkedHashMap(cache.snapshot())

    @Synchronized
    fun clear() {
        cache.evictAll()
    }
}
