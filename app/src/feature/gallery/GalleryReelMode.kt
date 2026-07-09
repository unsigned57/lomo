package com.lomo.app.feature.gallery

enum class GalleryReelMode {
    Gallery,
    SingleMemo,
}

val GalleryReelMode.allowsMemoPaging: Boolean
    get() = this == GalleryReelMode.Gallery
