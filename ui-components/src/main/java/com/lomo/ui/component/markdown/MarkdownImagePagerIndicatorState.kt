package com.lomo.ui.component.markdown

internal data class MarkdownImagePagerIndicatorState(
    val currentPage: Int,
    val pageCount: Int,
)

internal fun resolveMarkdownImagePagerIndicatorState(
    currentPage: Int,
    pageCount: Int,
): MarkdownImagePagerIndicatorState? {
    if (pageCount <= 0) return null
    return MarkdownImagePagerIndicatorState(
        currentPage = currentPage.coerceIn(0, pageCount - 1),
        pageCount = pageCount,
    )
}
