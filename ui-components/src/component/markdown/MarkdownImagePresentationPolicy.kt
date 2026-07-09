package com.lomo.ui.component.markdown

import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.AsyncImagePainter

internal enum class MarkdownImageLoadState {
    Empty,
    Loading,
    Success,
    Error,
}

internal enum class MarkdownImagePresentation {
    EmptyPlaceholder,
    LoadingPlaceholder,
    Success,
    RetainedSuccess,
    ErrorPlaceholder,
}

internal const val DEFAULT_MARKDOWN_IMAGE_LAYOUT_RATIO = 16f / 9f

internal fun resolveMarkdownImageLayoutRatio(
    cachedRatio: Float?,
    freshRatio: Float?,
): Float =
    freshRatio.validMarkdownImageRatio()
        ?: cachedRatio.validMarkdownImageRatio()
        ?: DEFAULT_MARKDOWN_IMAGE_LAYOUT_RATIO

internal fun resolveMarkdownImagePresentation(
    loadState: MarkdownImageLoadState,
    hasRetainedSuccess: Boolean,
): MarkdownImagePresentation =
    when (loadState) {
        MarkdownImageLoadState.Success -> MarkdownImagePresentation.Success
        MarkdownImageLoadState.Error -> MarkdownImagePresentation.ErrorPlaceholder
        MarkdownImageLoadState.Loading ->
            if (hasRetainedSuccess) {
                MarkdownImagePresentation.RetainedSuccess
            } else {
                MarkdownImagePresentation.LoadingPlaceholder
            }
        MarkdownImageLoadState.Empty ->
            if (hasRetainedSuccess) {
                MarkdownImagePresentation.RetainedSuccess
            } else {
                MarkdownImagePresentation.EmptyPlaceholder
            }
    }

internal fun AsyncImagePainter.State.successPainter(): Painter? =
    (this as? AsyncImagePainter.State.Success)?.painter

internal fun AsyncImagePainter.State.toMarkdownImageLoadState(): MarkdownImageLoadState =
    when (this) {
        is AsyncImagePainter.State.Success -> MarkdownImageLoadState.Success
        is AsyncImagePainter.State.Error -> MarkdownImageLoadState.Error
        is AsyncImagePainter.State.Loading -> MarkdownImageLoadState.Loading
        AsyncImagePainter.State.Empty -> MarkdownImageLoadState.Empty
    }

private fun Float?.validMarkdownImageRatio(): Float? =
    this?.takeIf { ratio -> ratio.isFinite() && ratio > 0f }
