package com.lomo.app.feature.gallery

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.Disposable
import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.feature.image.enqueueImagePreloadRequests
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Viewport-driven image warming for the mosaic. Bitmap preloads are capped and sized to the
 * gallery tile footprint, stale requests are cancelled as the window slides, and the same window
 * resolves tile aspect ratios ahead of composition so bands settle before they scroll in.
 */
@Composable
internal fun GalleryGridPreheatEffect(
    bands: List<GalleryMosaicLayout>,
    listState: LazyListState,
    onResolveImageAspect: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val planner = remember { GalleryImagePreheatPlanner() }
    val activePreloads = remember { mutableMapOf<String, Disposable>() }
    val latestResolver = rememberUpdatedState(onResolveImageAspect)

    DisposableEffect(imageLoader, planner, activePreloads) {
        onDispose {
            activePreloads.values.forEach(Disposable::dispose)
            activePreloads.clear()
            planner.reset()
        }
    }

    LaunchedEffect(bands, listState, context, imageLoader, planner) {
        snapshotFlow {
            GalleryBandViewport(
                firstVisible = listState.firstVisibleItemIndex,
                visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                viewportWidthPx = listState.layoutInfo.viewportSize.width,
            )
        }.distinctUntilChanged()
            .collectLatest { viewport ->
                val tileSize = resolveGalleryTilePreloadSize(viewport.viewportWidthPx)
                val plan =
                    if (viewport.visibleCount == 0) {
                        planner.planStartup(bands = bands, tileSize = tileSize)
                    } else {
                        planner.planViewport(
                            bands = bands,
                            firstVisibleBand = viewport.firstVisible,
                            visibleBandCount = viewport.visibleCount,
                            tileSize = tileSize,
                        )
                    }
                plan.cancelUrls.forEach { url ->
                    activePreloads.remove(url)?.dispose()
                }
                val specsToStart =
                    plan.startRequests
                        .filterNot { spec -> activePreloads[spec.url]?.isDisposed == false }
                activePreloads +=
                    enqueueImagePreloadRequests(
                        context = context,
                        imageLoader = imageLoader,
                        specs = specsToStart,
                    )
                plan.windowUrls.forEach { url ->
                    latestResolver.value.invoke(url)
                }
            }
    }
}

private data class GalleryBandViewport(
    val firstVisible: Int,
    val visibleCount: Int,
    val viewportWidthPx: Int,
)

private const val GALLERY_TILE_SPAN_DENOMINATOR = 2
private const val GALLERY_TILE_PRELOAD_MIN_EDGE_PX = 96

/** A hero tile spans at most half the mosaic's twelve columns; preheat at that footprint. */
private fun resolveGalleryTilePreloadSize(viewportWidthPx: Int): FeedImagePreloadSize {
    val edgePx = (viewportWidthPx / GALLERY_TILE_SPAN_DENOMINATOR).coerceAtLeast(GALLERY_TILE_PRELOAD_MIN_EDGE_PX)
    return FeedImagePreloadSize(widthPx = edgePx, heightPx = edgePx)
}
