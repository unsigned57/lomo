package com.lomo.app.feature.gallery

enum class GalleryReelChromeVisibility {
    Hidden,
    Visible,
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

