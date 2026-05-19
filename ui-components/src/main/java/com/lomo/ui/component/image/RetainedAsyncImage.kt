package com.lomo.ui.component.image

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * Remembers the most recent painter that successfully resolved for [modelKey].
 *
 * Returns `null` until [state] first reaches [AsyncImagePainter.State.Success];
 * thereafter returns the success painter and replaces it whenever a later
 * Success arrives for the same key. The retained painter is reset when
 * [modelKey] changes (since it would describe a different image).
 *
 * Use this when a caller needs to render a placeholder painter from the
 * previous successful load while a new model is still loading, or while it
 * has errored out, to avoid blank frames.
 */
@Composable
fun rememberRetainedSuccessPainter(
    modelKey: Any?,
    state: AsyncImagePainter.State,
): Painter? {
    var retained by remember(modelKey) { mutableStateOf<Painter?>(null) }
    val successPainter = (state as? AsyncImagePainter.State.Success)?.painter
    LaunchedEffect(modelKey, successPainter) {
        if (successPainter != null) {
            retained = successPainter
        }
    }
    return retained
}

/**
 * An [Image] backed by Coil's [rememberAsyncImagePainter] that retains the
 * previously successful painter while the model is being swapped or reloaded.
 *
 * Use this anywhere the call site cannot tolerate a blank frame between
 * model changes — gallery grid thumbnails, the gallery reel blur background,
 * etc. The retained painter is dropped as soon as a new Success state arrives
 * (or whenever the caller-supplied [model] resets to null).
 */
@Composable
fun RetainedAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val painter = rememberAsyncImagePainter(model)
    val state by painter.state.collectAsState()
    val retainedSuccessPainter = rememberRetainedSuccessPainter(modelKey = model, state = state)

    LaunchedEffect(state) {
        onState?.invoke(state)
    }

    val source =
        resolveRetainedAsyncImageSource(
            loadState = state.toRetainedAsyncImageLoadState(),
            hasRetainedSuccess = retainedSuccessPainter != null,
        )
    val renderedPainter: Painter =
        when (source) {
            RetainedAsyncImageSource.Current -> painter
            RetainedAsyncImageSource.Retained -> retainedSuccessPainter ?: painter
        }

    Image(
        painter = renderedPainter,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
    )
}

internal fun AsyncImagePainter.State.toRetainedAsyncImageLoadState(): RetainedAsyncImageLoadState =
    when (this) {
        is AsyncImagePainter.State.Success -> RetainedAsyncImageLoadState.Success
        is AsyncImagePainter.State.Error -> RetainedAsyncImageLoadState.Error
        is AsyncImagePainter.State.Loading -> RetainedAsyncImageLoadState.Loading
        AsyncImagePainter.State.Empty -> RetainedAsyncImageLoadState.Empty
    }
