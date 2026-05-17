package com.lomo.app.feature.gallery

internal sealed interface GalleryReelActiveMemoResolution {
    data object KeepActiveMemo : GalleryReelActiveMemoResolution

    data object PopRoute : GalleryReelActiveMemoResolution

    data class UpdateActiveMemo(
        val memoId: String,
    ) : GalleryReelActiveMemoResolution
}

internal fun resolveGalleryReelActiveMemo(
    viewerMode: GalleryReelMode,
    activeMemoId: String,
    requestMemoIds: List<String>,
): GalleryReelActiveMemoResolution {
    if (activeMemoId in requestMemoIds) {
        return GalleryReelActiveMemoResolution.KeepActiveMemo
    }
    if (requestMemoIds.isEmpty()) {
        return GalleryReelActiveMemoResolution.PopRoute
    }
    return when (viewerMode) {
        GalleryReelMode.Gallery -> GalleryReelActiveMemoResolution.PopRoute
        GalleryReelMode.SingleMemo -> GalleryReelActiveMemoResolution.UpdateActiveMemo(requestMemoIds.first())
    }
}
