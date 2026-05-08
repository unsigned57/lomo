package com.lomo.app.feature.gallery

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.LinkedHashMap

class GalleryImageDimensionResolver(
    private val maxEntries: Int = MAX_CACHE_ENTRIES,
) {
    private val cache =
        object : LinkedHashMap<String, Float>(maxEntries, STORE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
                size > maxEntries
        }
    private val _aspectFlow = MutableStateFlow<ImmutableMap<String, Float>>(persistentHashMapOf())

    val aspectFlow: StateFlow<ImmutableMap<String, Float>> = _aspectFlow.asStateFlow()

    suspend fun resolve(path: String): Float {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return GALLERY_DEFAULT_ASPECT_RATIO

        synchronized(cache) {
            cache[normalizedPath]?.let { return it }
        }

        val aspectRatio =
            withContext(Dispatchers.IO) {
                decodeAspectRatio(normalizedPath)
            }
        synchronized(cache) {
            cache[normalizedPath] = aspectRatio
            _aspectFlow.value = cache.toMap().toPersistentMap()
        }
        return aspectRatio
    }

    private fun decodeAspectRatio(path: String): Float {
        val filePath = path.toBitmapDecodePath()
        if (filePath == null) {
            Timber.tag(LOG_TAG).w("Unsupported gallery image path: $path")
            return GALLERY_DEFAULT_ASPECT_RATIO
        }
        if (!File(filePath).isFile) {
            Timber.tag(LOG_TAG).w("Gallery image file does not exist: $filePath")
            return GALLERY_DEFAULT_ASPECT_RATIO
        }

        return runCatching {
            val options =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeFile(filePath, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width > 0 && height > 0) {
                width.toFloat() / height.toFloat()
            } else {
                GALLERY_DEFAULT_ASPECT_RATIO
            }
        }.getOrElse { throwable ->
            Timber.tag(LOG_TAG).w(throwable, "Failed to decode gallery image bounds: $filePath")
            GALLERY_DEFAULT_ASPECT_RATIO
        }
    }

    private fun String.toBitmapDecodePath(): String? =
        when {
            startsWith(FILE_URI_PREFIX, ignoreCase = true) ->
                toUri().path

            startsWith(HTTP_URI_PREFIX, ignoreCase = true) ||
                startsWith(HTTPS_URI_PREFIX, ignoreCase = true) ||
                startsWith(CONTENT_URI_PREFIX, ignoreCase = true) ->
                null

            else -> this
        }

    private companion object {
        const val MAX_CACHE_ENTRIES = 256
        const val STORE_LOAD_FACTOR = 0.75f
        const val LOG_TAG = "GalleryImageResolver"
        const val FILE_URI_PREFIX = "file://"
        const val CONTENT_URI_PREFIX = "content://"
        const val HTTP_URI_PREFIX = "http://"
        const val HTTPS_URI_PREFIX = "https://"
    }
}
