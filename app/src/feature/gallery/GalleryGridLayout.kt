package com.lomo.app.feature.gallery

import kotlinx.collections.immutable.ImmutableList

const val GALLERY_PORTRAIT_ASPECT_MAX = 0.7f
const val GALLERY_LANDSCAPE_ASPECT_MIN = 1.4f
const val GALLERY_DEFAULT_ASPECT_RATIO = 1f
const val GALLERY_MOSAIC_COLUMN_COUNT = 12

enum class GalleryAspectKind {
    Portrait,
    Square,
    Landscape,
}

data class GalleryLayoutInput(
    val memoId: String,
    val firstImageUrl: String,
)

data class GalleryMosaicTile(
    val memoId: String,
    val firstImageUrl: String,
    val aspectKind: GalleryAspectKind,
    val aspectRatio: Float,
    val column: Int,
    val row: Int,
    val columnSpan: Int,
    val rowSpan: Int,
)

data class GalleryMosaicLayout(
    val tiles: ImmutableList<GalleryMosaicTile>,
    val rowCount: Int,
)

fun galleryLayoutInput(
    memoId: String,
    firstImageUrl: String,
): GalleryLayoutInput =
    GalleryLayoutInput(
        memoId = memoId,
        firstImageUrl = firstImageUrl,
    )

fun gallerySharedElementKey(
    memoId: String,
    imageIndex: Int,
): String = "gallery:$memoId:$imageIndex"

internal fun resolveGalleryAspectByMemoIdOrNull(
    layoutInputs: List<GalleryLayoutInput>,
    aspectByImageUrl: Map<String, Float>,
): Map<String, Float>? {
    val aspectByMemoId = LinkedHashMap<String, Float>(layoutInputs.size)
    layoutInputs.forEach { input ->
        aspectByMemoId[input.memoId] = sanitizeGalleryAspect(aspectByImageUrl[input.firstImageUrl])
    }
    return aspectByMemoId
}

internal fun sanitizeGalleryAspect(aspectRatio: Float?): Float =
    aspectRatio
        ?.takeIf { it.isFinite() && it > 0f }
        ?: GALLERY_DEFAULT_ASPECT_RATIO

internal fun galleryAspectKindFor(aspectRatio: Float): GalleryAspectKind =
    when {
        aspectRatio < GALLERY_PORTRAIT_ASPECT_MAX -> GalleryAspectKind.Portrait
        aspectRatio > GALLERY_LANDSCAPE_ASPECT_MIN -> GalleryAspectKind.Landscape
        else -> GalleryAspectKind.Square
    }
