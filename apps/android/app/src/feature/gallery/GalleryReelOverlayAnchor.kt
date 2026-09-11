package com.lomo.app.feature.gallery

enum class GalleryReelOverlayAnchor {
    Hidden,
    Collapsed,
    Expanded,
}

fun nextAnchorOnBack(current: GalleryReelOverlayAnchor): GalleryReelOverlayAnchor =
    when (current) {
        GalleryReelOverlayAnchor.Expanded -> GalleryReelOverlayAnchor.Collapsed
        GalleryReelOverlayAnchor.Collapsed -> GalleryReelOverlayAnchor.Hidden
        GalleryReelOverlayAnchor.Hidden -> GalleryReelOverlayAnchor.Hidden
    }

