package com.lomo.app.feature.image

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.lomo.app.BuildConfig
import kotlin.coroutines.EmptyCoroutineContext

internal fun enqueueImagePreloadRequests(
    context: Context,
    imageLoader: ImageLoader,
    urls: List<String>,
) {
    urls.forEach { url ->
        val request =
            ImageRequest
                .Builder(context)
                .memoryCacheKey(url)
                .placeholderMemoryCacheKey(url)
                .data(url)
                .apply {
                    if (!BuildConfig.DEBUG) {
                        interceptorCoroutineContext(EmptyCoroutineContext)
                    }
                }.build()
        imageLoader.enqueue(request)
    }
}
