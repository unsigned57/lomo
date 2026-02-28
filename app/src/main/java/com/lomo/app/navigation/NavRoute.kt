package com.lomo.app.navigation

import kotlinx.serialization.Serializable
import java.util.LinkedHashMap
import java.util.UUID

@Serializable
sealed interface NavRoute {
    @Serializable
    data object Main : NavRoute

    @Serializable
    data object Settings : NavRoute

    @Serializable
    data object Trash : NavRoute

    @Serializable
    data object Search : NavRoute

    @Serializable
    data class Tag(
        val tagName: String,
    ) : NavRoute

    @Serializable
    data class ImageViewer(
        val url: String,
    ) : NavRoute

    @Serializable
    data object DailyReview : NavRoute

    @Serializable
    data object Gallery : NavRoute

    @Serializable
    data class Share(
        val payloadKey: String,
        val memoTimestamp: Long,
    ) : NavRoute
}

/**
 * Keeps share payload out of route args to avoid oversized/unsafe navigation params.
 * Entries are one-time consumable and pruned by age/size.
 */
object ShareRoutePayloadStore {
    private data class Entry(
        val content: String,
        val createdAtMillis: Long,
    )

    private const val MAX_ENTRIES = 64
    private const val ENTRY_TTL_MILLIS = 10 * 60 * 1000L
    private val store = LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun putMemoContent(content: String): String {
        val now = System.currentTimeMillis()
        pruneLocked(now)
        trimLocked()

        val key = UUID.randomUUID().toString()
        store[key] = Entry(content = content, createdAtMillis = now)
        return key
    }

    @Synchronized
    fun consumeMemoContent(key: String): String? {
        val now = System.currentTimeMillis()
        pruneLocked(now)
        return store.remove(key)?.content
    }

    @Synchronized
    fun clearForTest() {
        store.clear()
    }

    private fun pruneLocked(now: Long) {
        val iterator = store.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.createdAtMillis > ENTRY_TTL_MILLIS) {
                iterator.remove()
            }
        }
    }

    private fun trimLocked() {
        while (store.size >= MAX_ENTRIES) {
            val oldestKey = store.entries.firstOrNull()?.key ?: return
            store.remove(oldestKey)
        }
    }
}
