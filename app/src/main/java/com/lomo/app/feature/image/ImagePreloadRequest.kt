package com.lomo.app.feature.image

import android.content.Context
import coil3.ImageLoader
import com.lomo.app.BuildConfig
import kotlin.coroutines.EmptyCoroutineContext

internal fun enqueueImagePreloadRequests(
    context: Context,
    imageLoader: ImageLoader,
    urls: List<String>,
) {
    urls.forEach { url ->
        val request =
            lomoSharedKeyImageRequest(context = context, url = url) {
                if (!BuildConfig.DEBUG) {
                    interceptorCoroutineContext(EmptyCoroutineContext)
                }
            }
        imageLoader.enqueue(request)
    }
}
