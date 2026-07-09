package com.lomo.app.feature.image

import android.content.Context
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.lomo.app.AppBuildInfo
import kotlin.coroutines.EmptyCoroutineContext

internal data class FeedImagePreloadSize(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive." }
        require(heightPx > 0) { "heightPx must be positive." }
    }
}

internal data class ImagePreloadSpec(
    val url: String,
    val size: FeedImagePreloadSize,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank." }
    }
}

internal fun enqueueImagePreloadRequests(
    context: Context,
    imageLoader: ImageLoader,
    specs: List<ImagePreloadSpec>,
): Map<String, Disposable> =
    buildMap(specs.size) {
        specs.forEach { spec ->
            put(
                spec.url,
                imageLoader.enqueue(createImagePreloadRequest(context = context, spec = spec)),
            )
        }
    }

internal fun createImagePreloadRequest(
    context: Context,
    spec: ImagePreloadSpec,
): ImageRequest =
    lomoSharedKeyImageRequest(context = context, url = spec.url) {
        size(spec.size.widthPx, spec.size.heightPx)
        scale(Scale.FILL)
        precision(Precision.INEXACT)
        if (!AppBuildInfo.isDebuggable(context)) {
            interceptorCoroutineContext(EmptyCoroutineContext)
        }
    }
