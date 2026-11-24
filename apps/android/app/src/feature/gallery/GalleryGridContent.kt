package com.lomo.app.feature.gallery

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.rememberConstraintsSizeResolver
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.lomoSharedKeyImageRequest
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.image.RetainedAsyncImage
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableMap
import kotlin.math.max

private val GALLERY_GRID_ITEM_SPACING = 6.dp
private val GALLERY_GRID_CELL_SHAPE = AppShapes.Large
private const val GALLERY_MOSAIC_CONTENT_TYPE = "gallery-mosaic"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGridContent(
    memos: List<GalleryUiItem>,
    onItemAccess: (Int) -> Unit,
    aspectByMemoId: ImmutableMap<String, Float>,
    contentPadding: PaddingValues,
    onCellClick: (memoId: String, imageIndex: Int) -> Unit,
    onShowMenu: (GalleryUiItem) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memoIndexById = remember(memos) { memos.withIndex().associate { it.value.memoId to it.index } }
    val memoById = remember(memos) { memos.associateBy { item -> item.memoId } }
    val layout =
        remember(memos, aspectByMemoId) {
            planGalleryMosaicLayout(
                memos =
                memos.mapNotNull { item ->
                    val firstImageUrl = item.imageUrls.firstOrNull() ?: return@mapNotNull null
                    galleryLayoutInput(
                        memoId = item.memoId,
                        firstImageUrl = firstImageUrl,
                    )
                },
                aspectByMemoId = aspectByMemoId,
            )
        }
    val cellContext =
        remember(memoById, onCellClick, onShowMenu, onResolveImageAspect) {
            GalleryMosaicCellContext(
                memoById = memoById,
                onCellClick = onCellClick,
                onShowMenu = onShowMenu,
                onResolveImageAspect = onResolveImageAspect,
            )
        }

    val listState = rememberLazyListState()
    GalleryGridPreheatEffect(
        bands = layout,
        listState = listState,
        onResolveImageAspect = onResolveImageAspect,
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(GALLERY_GRID_ITEM_SPACING),
    ) {
        items(
            items = layout,
            key = { mosaicLayout -> mosaicLayout.key },
            contentType = { GALLERY_MOSAIC_CONTENT_TYPE },
        ) { mosaicLayout ->
            LaunchedEffect(mosaicLayout, memoIndexById) {
                onItemAccess(galleryBandPagingIndex(mosaicLayout, memoIndexById))
            }
            GalleryMosaicBand(
                mosaicLayout = mosaicLayout,
                cellContext = cellContext,
            )
        }
    }
}

private data class GalleryMosaicCellContext(
    val memoById: Map<String, GalleryUiItem>,
    val onCellClick: (memoId: String, imageIndex: Int) -> Unit,
    val onShowMenu: (GalleryUiItem) -> Unit,
    val onResolveImageAspect: suspend (String) -> Unit,
)

@Composable
private fun GalleryMosaicBand(
    mosaicLayout: GalleryMosaicLayout,
    cellContext: GalleryMosaicCellContext,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            mosaicLayout.tiles.forEach { tile ->
                key(tile.memoId) {
                    GalleryMosaicCell(
                        cellContext = cellContext,
                        tile = tile,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val spacingPx = GALLERY_GRID_ITEM_SPACING.roundToPx()
        val layoutWidth = constraints.galleryMosaicWidth()
        val cellSize = galleryMosaicCellSize(layoutWidth, spacingPx)
        val layoutHeight = galleryMosaicSpanSize(
            cellSize = cellSize,
            spacingPx = spacingPx,
            span = mosaicLayout.rowCount,
        )
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val tile = mosaicLayout.tiles[index]
                measurable.measure(
                    Constraints.fixed(
                        width =
                        galleryMosaicSpanSize(
                            cellSize = cellSize,
                            spacingPx = spacingPx,
                            span = tile.columnSpan,
                        ),
                        height =
                        galleryMosaicSpanSize(
                            cellSize = cellSize,
                            spacingPx = spacingPx,
                            span = tile.rowSpan,
                        ),
                    ),
                )
            }

        layout(
            width = layoutWidth,
            height = constraints.galleryMosaicHeight(layoutHeight),
        ) {
            placeables.forEachIndexed { index, placeable ->
                val tile = mosaicLayout.tiles[index]
                placeable.placeRelative(
                    x = tile.column * (cellSize + spacingPx),
                    y = tile.row * (cellSize + spacingPx),
                )
            }
        }
    }
}

@Composable
private fun GalleryMosaicCell(
    cellContext: GalleryMosaicCellContext,
    tile: GalleryMosaicTile,
    modifier: Modifier = Modifier,
) {
    val item = cellContext.memoById[tile.memoId] ?: return
    GalleryGridCell(
        item = item,
        layout = tile,
        onCellClick = cellContext.onCellClick,
        onShowMenu = cellContext.onShowMenu,
        onResolveImageAspect = cellContext.onResolveImageAspect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridCell(
    item: GalleryUiItem,
    layout: GalleryMosaicTile,
    onCellClick: (memoId: String, imageIndex: Int) -> Unit,
    onShowMenu: (GalleryUiItem) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    val imageUrls = item.imageUrls
    if (imageUrls.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { imageUrls.size })
    LaunchedEffect(imageUrls.size) {
        val lastIndex = imageUrls.lastIndex
        if (lastIndex >= 0 && pagerState.currentPage > lastIndex) {
            pagerState.scrollToPage(lastIndex)
        }
    }

    Box(
        modifier =
        modifier
            // Anchor before clip/clickable: the tagged node IS the cell, so the
            // harness resolves a settled clickable cell directly.
            .benchmarkAnchor(BenchmarkAnchorContract.galleryCell(item.memoId))
            .clip(GALLERY_GRID_CELL_SHAPE)
            .combinedClickable(
                onClick = {
                    onCellClick(
                        item.memoId,
                        pagerState.currentPage.coerceIn(0, imageUrls.lastIndex),
                    )
                },
                onLongClick = {
                    haptic.longPress()
                    onShowMenu(item)
                },
            ),
    ) {
        if (imageUrls.size == 1) {
            GalleryGridImage(
                memoId = item.memoId,
                imageUrl = layout.firstImageUrl,
                imageIndex = 0,
                onResolveImageAspect = onResolveImageAspect,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) { page ->
                GalleryGridImage(
                    memoId = item.memoId,
                    imageUrl = imageUrls[page],
                    imageIndex = page,
                    onResolveImageAspect = onResolveImageAspect,
                )
            }
        }
    }
}

private val GalleryMosaicLayout.key: String
    get() = tiles.joinToString(separator = "|") { tile -> tile.memoId }

private fun Constraints.galleryMosaicWidth(): Int = if (maxWidth == Constraints.Infinity) {
    minWidth
} else {
    maxWidth
}

private fun Constraints.galleryMosaicHeight(layoutHeight: Int): Int = if (maxHeight == Constraints.Infinity) {
    max(minHeight, layoutHeight)
} else {
    layoutHeight.coerceIn(minHeight, maxHeight)
}

private fun galleryMosaicCellSize(layoutWidth: Int, spacingPx: Int): Int {
    val spacingWidth = spacingPx * (GALLERY_MOSAIC_COLUMN_COUNT - 1)
    return max(
        1,
        (layoutWidth - spacingWidth) / GALLERY_MOSAIC_COLUMN_COUNT,
    )
}

private fun galleryMosaicSpanSize(cellSize: Int, spacingPx: Int, span: Int): Int = if (span <= 0) {
    0
} else {
    span * cellSize + (span - 1) * spacingPx
}

@Composable
private fun GalleryGridImage(
    memoId: String,
    imageUrl: String,
    imageIndex: Int,
    onResolveImageAspect: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val model =
        remember(imageUrl, context, sizeResolver) {
            lomoSharedKeyImageRequest(context = context, url = imageUrl) { size(sizeResolver) }
        }
    LaunchedEffect(imageUrl) {
        onResolveImageAspect(imageUrl)
    }
    RetainedAsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
        Modifier
            .fillMaxSize()
            .then(sizeResolver)
            .rememberGallerySharedElementModifier(
                memoId = memoId,
                imageIndex = imageIndex,
            ),
    )
}

@Composable
private fun Modifier.rememberGallerySharedElementModifier(memoId: String, imageIndex: Int): Modifier {
    val sharedTransitionScope = com.lomo.ui.util.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.lomo.ui.util.LocalAnimatedVisibilityScope.current

    @OptIn(ExperimentalSharedTransitionApi::class)
    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            sharedElement(
                rememberSharedContentState(key = gallerySharedElementKey(memoId, imageIndex)),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        this
    }
}
