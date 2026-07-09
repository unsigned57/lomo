package com.lomo.app.feature.gallery

enum class GalleryReelChromeVisibility {
    Hidden,
    Visible,
}

enum class GalleryReelBackAction {
    PopRoute,
}

fun toggleGalleryReelChromeVisibility(current: GalleryReelChromeVisibility): GalleryReelChromeVisibility =
    when (current) {
        GalleryReelChromeVisibility.Visible -> GalleryReelChromeVisibility.Hidden
        GalleryReelChromeVisibility.Hidden -> GalleryReelChromeVisibility.Visible
    }

fun nextChromeVisibilityOnPageChange(current: GalleryReelChromeVisibility): GalleryReelChromeVisibility =
    when (current) {
        GalleryReelChromeVisibility.Visible -> GalleryReelChromeVisibility.Visible
        GalleryReelChromeVisibility.Hidden -> GalleryReelChromeVisibility.Hidden
    }

fun resolveGalleryReelBackAction(chromeVisibility: GalleryReelChromeVisibility): GalleryReelBackAction =
    when (chromeVisibility) {
        GalleryReelChromeVisibility.Visible -> GalleryReelBackAction.PopRoute
        GalleryReelChromeVisibility.Hidden -> GalleryReelBackAction.PopRoute
    }
