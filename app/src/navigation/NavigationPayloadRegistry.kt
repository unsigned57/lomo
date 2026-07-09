package com.lomo.app.navigation

import com.lomo.ui.util.SynchronizedLruStore
import java.util.UUID

class NavigationPayloadRegistry<T : Any>(
    maxEntries: Int,
    private val ttlMillis: Long,
    private var clock: () -> Long = System::currentTimeMillis,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Entry<T : Any>(
        val payload: T,
        val createdAtMillis: Long,
    )

    private val store = SynchronizedLruStore<String, Entry<T>>(maxEntries)

    @Synchronized
    fun put(payload: T): String {
        val now = clock()
        pruneLocked(now)

        val key = keyFactory()
        store.put(key, Entry(payload = payload, createdAtMillis = now))
        return key
    }

    @Synchronized
    fun get(key: String): T? {
        val now = clock()
        pruneLocked(now)
        return store.get(key)?.payload
    }

    @Synchronized
    fun remove(key: String): T? {
        val now = clock()
        pruneLocked(now)
        return store.remove(key)?.payload
    }

    @Synchronized
    fun clear() {
        store.clear()
    }

    @Synchronized
    fun setClockForTest(testClock: () -> Long) {
        clock = testClock
    }

    private fun pruneLocked(now: Long) {
        store.snapshot().forEach { (key, entry) ->
            if (now - entry.createdAtMillis > ttlMillis) {
                store.remove(key)
            }
        }
    }
}
