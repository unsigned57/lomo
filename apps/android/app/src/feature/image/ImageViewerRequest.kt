package com.lomo.app.feature.image

data class ImageViewerRequest(
    val imageUrls: List<String>,
    val initialIndex: Int,
    val memoId: String? = null,
)

fun createImageViewerRequest(
    imageUrls: List<String>,
    clickedUrl: String,
    memoId: String? = null,
): ImageViewerRequest {
    val normalizedUrls =
        imageUrls
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    if (normalizedUrls.isEmpty()) {
        val fallbackUrl = clickedUrl.trim()
        return ImageViewerRequest(
            imageUrls = if (fallbackUrl.isNotEmpty()) listOf(fallbackUrl) else emptyList(),
            initialIndex = 0,
            memoId = memoId,
        )
    }

    val index = normalizedUrls.indexOf(clickedUrl.trim()).takeIf { it >= 0 } ?: 0
    return ImageViewerRequest(
        imageUrls = normalizedUrls,
        initialIndex = index,
        memoId = memoId,
    )
}
