package com.lomo.app.feature.gallery

const val GALLERY_PORTRAIT_ASPECT_MAX = 0.7f
const val GALLERY_LANDSCAPE_ASPECT_MIN = 1.4f
const val GALLERY_DEFAULT_ASPECT_RATIO = 1f
const val GALLERY_DEFAULT_HIGHLIGHT_STRIDE = 8

enum class GalleryAspectKind {
    Portrait,
    Square,
    Landscape,
}

data class GalleryLayoutInput(
    val memoId: String,
    val firstImageUrl: String,
)

data class GalleryCellLayout(
    val memoId: String,
    val firstImageUrl: String,
    val aspectKind: GalleryAspectKind,
    val aspectRatio: Float,
    val isHighlight: Boolean,
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

fun planGalleryLayout(
    memos: List<GalleryLayoutInput>,
    aspectByMemoId: Map<String, Float>,
    highlightStride: Int = GALLERY_DEFAULT_HIGHLIGHT_STRIDE,
): List<GalleryCellLayout> =
    memos.mapIndexed { index, memo ->
        val resolvedAspect = sanitizeGalleryAspect(aspectByMemoId[memo.memoId])
        val aspectKind = resolvedAspect.toGalleryAspectKind()
        val isHighlight =
            highlightStride > 0 &&
                index % highlightStride == 0 &&
                aspectKind == GalleryAspectKind.Square

        GalleryCellLayout(
            memoId = memo.memoId,
            firstImageUrl = memo.firstImageUrl,
            aspectKind = aspectKind,
            aspectRatio = if (isHighlight) GALLERY_DEFAULT_ASPECT_RATIO else resolvedAspect,
            isHighlight = isHighlight,
        )
    }

private fun sanitizeGalleryAspect(aspectRatio: Float?): Float =
    aspectRatio
        ?.takeIf { it.isFinite() && it > 0f }
        ?: GALLERY_DEFAULT_ASPECT_RATIO

private fun Float.toGalleryAspectKind(): GalleryAspectKind =
    when {
        this < GALLERY_PORTRAIT_ASPECT_MAX -> GalleryAspectKind.Portrait
        this > GALLERY_LANDSCAPE_ASPECT_MIN -> GalleryAspectKind.Landscape
        else -> GalleryAspectKind.Square
    }
