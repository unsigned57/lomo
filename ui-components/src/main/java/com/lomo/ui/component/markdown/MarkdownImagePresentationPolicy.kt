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
