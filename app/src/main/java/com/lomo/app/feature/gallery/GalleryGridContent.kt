package com.lomo.app.feature.gallery

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

private const val GALLERY_GRID_COLUMNS = 3
private val GALLERY_GRID_ITEM_SPACING = 6.dp
private val GALLERY_GRID_CELL_SHAPE = RoundedCornerShape(12.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGridContent(
    memos: ImmutableList<MemoUiModel>,
    aspectByMemoId: ImmutableMap<String, Float>,
    dateFormat: String,
    timeFormat: String,
    contentPadding: PaddingValues,
    onCellClick: (memoId: String, imageIndex: Int) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memoById = remember(memos) { memos.associateBy { uiModel -> uiModel.memo.id } }
    val layout =
        remember(memos, aspectByMemoId) {
            planGalleryLayout(
                memos =
                    memos.mapNotNull { uiModel ->
                        val firstImageUrl = uiModel.imageUrls.firstOrNull() ?: return@mapNotNull null
                        galleryLayoutInput(
                            memoId = uiModel.memo.id,
                            firstImageUrl = firstImageUrl,
                        )
                    },
                aspectByMemoId = aspectByMemoId,
            )
        }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(GALLERY_GRID_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalItemSpacing = GALLERY_GRID_ITEM_SPACING,
        horizontalArrangement = Arrangement.spacedBy(GALLERY_GRID_ITEM_SPACING),
    ) {
        itemsIndexed(
            items = layout,
            key = { _, cell -> cell.memoId },
            contentType = { _, cell -> cell.aspectKind },
            span = { _, cell ->
                if (cell.isHighlight) {
                    StaggeredGridItemSpan.FullLine
                } else {
                    StaggeredGridItemSpan.SingleLane
                }
            },
        ) { _, cell ->
            val uiModel = memoById[cell.memoId] ?: return@itemsIndexed
            GalleryGridCell(
                uiModel = uiModel,
                layout = cell,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onCellClick = onCellClick,
                onShowMenu = onShowMenu,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridCell(
    uiModel: MemoUiModel,
    layout: GalleryCellLayout,
    dateFormat: String,
    timeFormat: String,
    onCellClick: (memoId: String, imageIndex: Int) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
) {
    val imageUrls = uiModel.imageUrls
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
            Modifier
                .fillMaxWidth()
                .aspectRatio(layout.aspectRatio)
                .clip(GALLERY_GRID_CELL_SHAPE)
                .combinedClickable(
                    onClick = {
                        onCellClick(
                            uiModel.memo.id,
                            pagerState.currentPage.coerceIn(0, imageUrls.lastIndex),
                        )
                    },
                    onLongClick = {
                        onShowMenu(
                            memoMenuState(
                                memo = uiModel.memo,
                                dateFormat = dateFormat,
                                timeFormat = timeFormat,
                                imageUrls = imageUrls,
                            ),
                        )
                    },
                ),
    ) {
        if (imageUrls.size == 1) {
            GalleryGridImage(
                memoId = uiModel.memo.id,
                imageUrl = layout.firstImageUrl,
                imageIndex = 0,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) { page ->
                GalleryGridImage(
                    memoId = uiModel.memo.id,
                    imageUrl = imageUrls[page],
                    imageIndex = page,
                )
            }
        }
    }
}

@Composable
private fun GalleryGridImage(
    memoId: String,
    imageUrl: String,
    imageIndex: Int,
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .fillMaxSize()
                .rememberGallerySharedElementModifier(
                    memoId = memoId,
                    imageIndex = imageIndex,
                ),
    )
}

@Composable
private fun Modifier.rememberGallerySharedElementModifier(
    memoId: String,
    imageIndex: Int,
): Modifier {
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

