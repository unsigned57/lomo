package com.lomo.app.feature.image

import android.content.Context
import coil3.request.ImageRequest

/**
 * Builds a Coil [ImageRequest] that uses the url itself as both the memory
 * cache key and the placeholder memory cache key, so that all consumers of a
 * given url (gallery grid, gallery reel blur background, preloader, etc.)
 * share the same cache slot regardless of the decoded display size Coil would
 * otherwise derive from layout.
 */
internal fun lomoSharedKeyImageRequest(
    context: Context,
    url: String,
    configure: ImageRequest.Builder.() -> Unit = {},
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(url)
        .memoryCacheKey(url)
        .placeholderMemoryCacheKey(url)
        .apply(configure)
        .build()
