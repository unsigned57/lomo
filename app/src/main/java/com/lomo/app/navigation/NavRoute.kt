package com.lomo.app.navigation

import kotlinx.serialization.Serializable
import com.lomo.ui.util.SynchronizedLruStore
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
        val payloadKey: String,
        val initialIndex: Int,
        val memoId: String? = null,
    ) : NavRoute

    @Serializable
    data object DailyReview : NavRoute

    @Serializable
    data object Gallery : NavRoute

    @Serializable
    data class GalleryReel(
        val payloadKey: String,
        val initialMemoIndex: Int,
        val initialImageIndex: Int,
    ) : NavRoute

    @Serializable
    data object Statistics : NavRoute

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
    private val store = SynchronizedLruStore<String, Entry>(MAX_ENTRIES)

    @Synchronized
    fun putMemoContent(content: String): String {
        val now = System.currentTimeMillis()
        pruneLocked(now)

        val key = UUID.randomUUID().toString()
        store.put(key, Entry(content = content, createdAtMillis = now))
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
        store.snapshot().forEach { (key, entry) ->
            if (now - entry.createdAtMillis > ENTRY_TTL_MILLIS) {
                store.remove(key)
            }
        }
    }
}

/**
 * Keeps image viewer payload out of route args to avoid oversized navigation params.
 * Entries are cached and pruned by age/size so viewer state survives recomposition/config changes.
 */
object ImageViewerRoutePayloadStore {
    private data class Entry(
        val imageUrls: List<String>,
        val createdAtMillis: Long,
    )

    private const val MAX_ENTRIES = 64
    private const val ENTRY_TTL_MILLIS = 10 * 60 * 1000L
    private val store = SynchronizedLruStore<String, Entry>(MAX_ENTRIES)

    @Synchronized
    fun putImageUrls(imageUrls: List<String>): String {
        val now = System.currentTimeMillis()
        pruneLocked(now)

        val key = UUID.randomUUID().toString()
        store.put(
            key,
            Entry(
                imageUrls =
                    imageUrls
                        .asSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toList(),
                createdAtMillis = now,
            ),
        )
        return key
    }

    @Synchronized
    fun getImageUrls(key: String): List<String>? {
        val now = System.currentTimeMillis()
        pruneLocked(now)
        return store.get(key)?.imageUrls
    }

    @Synchronized
    fun clearForTest() {
        store.clear()
    }

    private fun pruneLocked(now: Long) {
        store.snapshot().forEach { (key, entry) ->
            if (now - entry.createdAtMillis > ENTRY_TTL_MILLIS) {
                store.remove(key)
            }
        }
    }
}

/**
 * Keeps gallery reel snapshots out of route args and preserves the opened gallery order.
 * Entries are cached and pruned by age/size so a configuration change can restore the reel.
 */
object GalleryReelPayloadStore {
    data class Payload(
        val memoIds: List<String>,
        val aspectByMemoId: Map<String, Float>,
    )

    private data class Entry(
        val payload: Payload,
        val createdAtMillis: Long,
    )

    const val MAX_ENTRIES_FOR_TEST = 64
    const val ENTRY_TTL_MILLIS_FOR_TEST = 10 * 60 * 1000L
    private val store = SynchronizedLruStore<String, Entry>(MAX_ENTRIES_FOR_TEST)
    private var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    fun put(payload: Payload): String {
        val now = clock()
        pruneLocked(now)

        val key = UUID.randomUUID().toString()
        store.put(
            key,
            Entry(
                payload =
                    Payload(
                        memoIds =
                            payload.memoIds
                                .asSequence()
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .distinct()
                                .toList(),
                        aspectByMemoId =
                            payload.aspectByMemoId
                                .filterKeys { key -> key.isNotBlank() }
                                .filterValues { aspect -> aspect.isFinite() && aspect > 0f },
                    ),
                createdAtMillis = now,
            ),
        )
        return key
    }

    @Synchronized
    fun get(key: String): Payload? {
        val now = clock()
        pruneLocked(now)
        return store.get(key)?.payload
    }

    @Synchronized
    fun clearForTest() {
        store.clear()
        clock = System::currentTimeMillis
    }

    @Synchronized
    fun setClockForTest(testClock: () -> Long) {
        clock = testClock
    }

    private fun pruneLocked(now: Long) {
        store.snapshot().forEach { (key, entry) ->
            if (now - entry.createdAtMillis > ENTRY_TTL_MILLIS_FOR_TEST) {
                store.remove(key)
            }
        }
    }
}
