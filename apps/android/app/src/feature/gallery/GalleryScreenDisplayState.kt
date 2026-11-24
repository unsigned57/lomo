package com.lomo.app.feature.gallery

import com.lomo.app.feature.main.GalleryUiMemosState
import kotlinx.collections.immutable.ImmutableMap

internal sealed interface GalleryScreenDisplayState {
    data object Loading : GalleryScreenDisplayState

    data object Empty : GalleryScreenDisplayState

    data class Grid(val memos: List<GalleryUiItem>, val aspectByMemoId: ImmutableMap<String, Float>) :
        GalleryScreenDisplayState
}

internal fun resolveGalleryScreenDisplayState(
    galleryState: GalleryUiMemosState,
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
): GalleryScreenDisplayState = when (galleryState) {
    GalleryUiMemosState.Loading -> GalleryScreenDisplayState.Loading

    is GalleryUiMemosState.Loaded ->
        when {
            galleryState.memos.isEmpty() -> GalleryScreenDisplayState.Empty

            // Wait for entry geometry; visible tiles decode independently at their display size.
            aspectByMemoId == null || !aspectsReady -> GalleryScreenDisplayState.Loading

            else ->
                GalleryScreenDisplayState.Grid(
                    memos = galleryState.memos,
                    aspectByMemoId = aspectByMemoId,
                )
        }
}
