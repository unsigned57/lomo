package com.lomo.app.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

internal object NavRouteSerialNames {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val SEARCH = "search"
    const val TAG = "tag"
    const val IMAGE_VIEWER = "imageViewer"
    const val DAILY_REVIEW = "dailyReview"
    const val GALLERY = "gallery"
    const val GALLERY_REEL = "galleryReel"
    const val STATISTICS = "statistics"
    const val SHARE = "share"
}

@Serializable
sealed interface NavRoute {
    @Serializable
    @SerialName(NavRouteSerialNames.MAIN)
    data object Main : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.SETTINGS)
    data object Settings : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.TRASH)
    data object Trash : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.SEARCH)
    data object Search : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.TAG)
    data class Tag(
        val tagName: String,
    ) : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.IMAGE_VIEWER)
    data class ImageViewer(
        val url: String,
        val payloadKey: String,
        val initialIndex: Int,
        val memoId: String? = null,
    ) : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.DAILY_REVIEW)
    data object DailyReview : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.GALLERY)
    data object Gallery : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.GALLERY_REEL)
    data class GalleryReel(
        val payloadKey: String,
        val initialMemoIndex: Int,
        val initialImageIndex: Int,
    ) : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.STATISTICS)
    data object Statistics : NavRoute

    @Serializable
    @SerialName(NavRouteSerialNames.SHARE)
    data class Share(
        val payloadKey: String,
        val memoTimestamp: Long,
    ) : NavRoute
}

/**
 * Keeps share payload out of route args to avoid oversized/unsafe navigation params.
 * Entries are one-time consumable per process and pruned by age/size.
 */
object ShareRoutePayloadStore {
    private const val MAX_ENTRIES = 64
    private const val ENTRY_TTL_MILLIS = 10 * 60 * 1000L
    private val registry =
        NavigationPayloadRegistry<String>(
            maxEntries = MAX_ENTRIES,
            ttlMillis = ENTRY_TTL_MILLIS,
        )
    private val consumedKeys = mutableSetOf<String>()
    @Volatile
    private var persistentCache: ShareRoutePayloadPersistentCache? = null

    @Synchronized
    fun putMemoContent(content: String): String {
        val key = registry.put(content)
        consumedKeys.remove(key)
        persistentCache?.put(key = key, payload = content)
        return key
    }

    @Synchronized
    fun consumeMemoContent(key: String): String? {
        if (key in consumedKeys) {
            return null
        }
        val memoryContent = registry.remove(key)
        if (memoryContent != null) {
            if (persistentCache?.discard(key) == false) {
                return null
            }
            consumedKeys += key
            return memoryContent
        }
        return persistentCache?.consume(key)?.also {
            consumedKeys += key
        }
    }

    @Synchronized
    internal fun configurePersistentCache(cacheDir: File) {
        persistentCache =
            ShareRoutePayloadPersistentCache(
                directory = cacheDir,
                maxEntries = MAX_ENTRIES,
                ttlMillis = ENTRY_TTL_MILLIS,
            )
    }

    @Synchronized
    fun clearForTest() {
        registry.clear()
        consumedKeys.clear()
        persistentCache?.clear()
        persistentCache = null
    }

    @Synchronized
    fun clearMemoryForTest() {
        registry.clear()
        consumedKeys.clear()
    }

    @Synchronized
    fun configurePersistentCacheForTest(cacheDir: File) {
        configurePersistentCache(cacheDir)
        consumedKeys.clear()
        persistentCache?.clear()
    }
}

/**
 * Keeps image viewer payload out of route args to avoid oversized navigation params.
 * Entries are cached and pruned by age/size so viewer state survives recomposition/config changes.
 */
object ImageViewerRoutePayloadStore {
    private const val MAX_ENTRIES = 64
    private const val ENTRY_TTL_MILLIS = 10 * 60 * 1000L
    private val registry =
        NavigationPayloadRegistry<List<String>>(
            maxEntries = MAX_ENTRIES,
            ttlMillis = ENTRY_TTL_MILLIS,
        )

    fun putImageUrls(imageUrls: List<String>): String {
        return registry.put(
            imageUrls
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList(),
        )
    }

    fun getImageUrls(key: String): List<String>? {
        return registry.get(key)
    }

    fun clearForTest() {
        registry.clear()
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

    const val MAX_ENTRIES_FOR_TEST = 64
    const val ENTRY_TTL_MILLIS_FOR_TEST = 10 * 60 * 1000L
    private val registry =
        NavigationPayloadRegistry<Payload>(
            maxEntries = MAX_ENTRIES_FOR_TEST,
            ttlMillis = ENTRY_TTL_MILLIS_FOR_TEST,
        )

    fun put(payload: Payload): String {
        return registry.put(
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
        )
    }

    fun get(key: String): Payload? {
        return registry.get(key)
    }

    fun clearForTest() {
        registry.clear()
        registry.setClockForTest(System::currentTimeMillis)
    }

    fun setClockForTest(testClock: () -> Long) {
        registry.setClockForTest(testClock)
    }
}
