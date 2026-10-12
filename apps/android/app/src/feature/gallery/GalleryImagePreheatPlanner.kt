package com.lomo.app.feature.gallery

import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.feature.image.ImagePreloadSpec

/**
 * One bounded preheat window over the mosaic bands: the bitmap requests to start (capped at
 * [GalleryImagePreheatPlanner]'s active-request bound), the urls whose in-flight preloads are now
 * stale and must be cancelled, and the tile urls inside the window whose aspect ratio should be
 * resolved. Aspect resolution follows the same window so bands resolve while still offscreen;
 * a band below the window keeps its default ratio until the window slides onto it, and resolving
 * can only repack bands at or below that point — none of them visible.
 */
internal data class GalleryImagePreheatPlan(
    val startRequests: List<ImagePreloadSpec>,
    val cancelUrls: Set<String>,
    val windowUrls: List<String>,
)

/**
 * Gallery counterpart of the feed's viewport preheater. Where the feed plans over memo indices,
 * the gallery's visible unit is a mosaic band, so the window is expressed in band indices of the
 * computed layout. Each in-window band contributes its tiles' first image urls; pager pages beyond
 * the first are not preheated.
 */
internal class GalleryImagePreheatPlanner(
    private val lookaheadBandCount: Int = DEFAULT_LOOKAHEAD_BAND_COUNT,
    private val startupBandCount: Int = DEFAULT_STARTUP_BAND_COUNT,
    private val maxActiveRequests: Int = DEFAULT_MAX_ACTIVE_REQUESTS,
) {
    private var activeRequestsByUrl: Map<String, FeedImagePreloadSize> = emptyMap()

    init {
        require(lookaheadBandCount >= 0) { "lookaheadBandCount must be non-negative." }
        require(startupBandCount >= 0) { "startupBandCount must be non-negative." }
        require(maxActiveRequests > 0) { "maxActiveRequests must be positive." }
    }

    fun planStartup(
        bands: List<GalleryMosaicLayout>,
        tileSize: FeedImagePreloadSize,
    ): GalleryImagePreheatPlan =
        planForBands(
            bands = bands.asSequence().take(startupBandCount),
            tileSize = tileSize,
        )

    fun planViewport(
        bands: List<GalleryMosaicLayout>,
        firstVisibleBand: Int,
        visibleBandCount: Int,
        tileSize: FeedImagePreloadSize,
    ): GalleryImagePreheatPlan {
        val safeFirstVisible = firstVisibleBand.coerceAtLeast(0)
        val safeVisibleCount = visibleBandCount.coerceAtLeast(0)
        return planForBands(
            bands =
                bands
                    .asSequence()
                    .drop(safeFirstVisible)
                    .take(safeVisibleCount + lookaheadBandCount),
            tileSize = tileSize,
        )
    }

    fun reset() {
        activeRequestsByUrl = emptyMap()
    }

    private fun planForBands(
        bands: Sequence<GalleryMosaicLayout>,
        tileSize: FeedImagePreloadSize,
    ): GalleryImagePreheatPlan {
        val desiredUrls = LinkedHashSet<String>(maxActiveRequests)
        for (band in bands) {
            for (tile in band.tiles) {
                val url = tile.firstImageUrl.trim()
                if (url.isEmpty()) {
                    continue
                }
                desiredUrls += url
                if (desiredUrls.size >= maxActiveRequests) {
                    return buildPlan(desiredUrls = desiredUrls, tileSize = tileSize)
                }
            }
        }
        return buildPlan(desiredUrls = desiredUrls, tileSize = tileSize)
    }

    private fun buildPlan(
        desiredUrls: LinkedHashSet<String>,
        tileSize: FeedImagePreloadSize,
    ): GalleryImagePreheatPlan {
        val desiredRequestsByUrl = desiredUrls.associateWith { tileSize }
        val urlsLeavingWindow = activeRequestsByUrl.keys - desiredRequestsByUrl.keys
        val urlsWithStaleSize =
            desiredRequestsByUrl
                .filter { (url, size) -> activeRequestsByUrl[url]?.let { active -> active != size } == true }
                .keys
        val requestsToStart =
            desiredUrls
                .asSequence()
                .filter { url -> activeRequestsByUrl[url] != tileSize }
                .map { url -> ImagePreloadSpec(url = url, size = tileSize) }
                .toList()
        activeRequestsByUrl = desiredRequestsByUrl
        return GalleryImagePreheatPlan(
            startRequests = requestsToStart,
            cancelUrls = urlsLeavingWindow + urlsWithStaleSize,
            windowUrls = desiredUrls.toList(),
        )
    }

    private companion object {
        private const val DEFAULT_LOOKAHEAD_BAND_COUNT = 2
        private const val DEFAULT_STARTUP_BAND_COUNT = 2
        private const val DEFAULT_MAX_ACTIVE_REQUESTS = 12
    }
}

/**
 * The entry gate's aspect/initial-image window: only the first [GALLERY_ENTRY_TILE_COUNT] tiles
 * must have resolved aspects before the mosaic mounts, so entry work stays O(entry window)
 * regardless of how much paging has loaded. The grid's viewport preheater takes over afterwards.
 */
internal const val GALLERY_ENTRY_TILE_COUNT = 24

internal fun galleryEntryWindowUrls(layoutInputs: List<GalleryLayoutInput>): List<String> =
    layoutInputs
        .asSequence()
        .map { input -> input.firstImageUrl.trim() }
        .filter { url -> url.isNotEmpty() }
        .distinct()
        .take(GALLERY_ENTRY_TILE_COUNT)
        .toList()
