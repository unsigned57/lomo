package com.lomo.app.feature.gallery

import com.lomo.app.feature.main.GalleryUiMemosState
import com.lomo.app.feature.main.MemoUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList

internal sealed interface GalleryScreenDisplayState {
    data object Loading : GalleryScreenDisplayState

    data object Empty : GalleryScreenDisplayState

    data class Grid(
        val memos: ImmutableList<MemoUiModel>,
        val aspectByMemoId: ImmutableMap<String, Float>,
    ) : GalleryScreenDisplayState
}

internal fun resolveGalleryScreenDisplayState(
    galleryState: GalleryUiMemosState,
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
    initialImagesReady: Boolean,
): GalleryScreenDisplayState =
    when (galleryState) {
        GalleryUiMemosState.Loading -> GalleryScreenDisplayState.Loading
        is GalleryUiMemosState.Loaded ->
            when {
                galleryState.memos.isEmpty() -> GalleryScreenDisplayState.Empty
                // Hold the loading state until every tile's aspect ratio is resolved so the mosaic
                // is laid out once at its final shape, instead of reflowing as ratios decode.
                aspectByMemoId == null || !aspectsReady || !initialImagesReady -> GalleryScreenDisplayState.Loading
                else ->
                    GalleryScreenDisplayState.Grid(
                        memos = galleryState.memos.toImmutableList(),
                        aspectByMemoId = aspectByMemoId,
                    )
            }
    }
