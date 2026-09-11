package com.lomo.ui.component.image

/**
 * Coarse load state for an async image, mirroring the subset of Coil
 * [coil3.compose.AsyncImagePainter.State] cases that influence whether a
 * retained successful painter should be drawn instead of the current one.
 */
enum class RetainedAsyncImageLoadState {
    Loading,
    Success,
    Error,
    Empty,
}

/**
 * Which painter [RetainedAsyncImage] should currently render.
 */
enum class RetainedAsyncImageSource {
    /** Draw the painter for the currently requested model. */
    Current,

    /** Draw the most recently successful painter to bridge a model swap. */
    Retained,
}

/**
 * Pure policy that decides whether to render the current painter or the
 * retained-success painter. Success always wins so a fresh image replaces the
 * retained one as soon as it arrives; non-success states prefer the retained
 * painter when one exists so the UI never falls back to a blank frame
 * mid-swap.
 */
fun resolveRetainedAsyncImageSource(
    loadState: RetainedAsyncImageLoadState,
    hasRetainedSuccess: Boolean,
): RetainedAsyncImageSource =
    when (loadState) {
        RetainedAsyncImageLoadState.Success -> RetainedAsyncImageSource.Current
        RetainedAsyncImageLoadState.Loading,
        RetainedAsyncImageLoadState.Empty,
        RetainedAsyncImageLoadState.Error,
        ->
            if (hasRetainedSuccess) {
                RetainedAsyncImageSource.Retained
            } else {
                RetainedAsyncImageSource.Current
            }
    }
