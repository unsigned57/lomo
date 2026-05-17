package com.lomo.app.feature.gallery

import android.content.ContentResolver
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.lomo.ui.util.SynchronizedLruStore
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

class GalleryImageDimensionResolver(
    private val contentResolver: ContentResolver? = null,
    private val maxEntries: Int = MAX_CACHE_ENTRIES,
) {
    private val cache = SynchronizedLruStore<String, Float>(maxEntries)
    private val _aspectFlow = MutableStateFlow(seedCacheFromShared())

    val aspectFlow: StateFlow<ImmutableMap<String, Float>> = _aspectFlow.asStateFlow()

    private fun seedCacheFromShared(): ImmutableMap<String, Float> {
        sharedCache.snapshot().forEach { (path, aspectRatio) ->
            cache.put(path, aspectRatio)
        }
        return cache.snapshot().toPersistentMap()
    }

    suspend fun resolve(path: String): Float {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty()) return GALLERY_DEFAULT_ASPECT_RATIO

        cache.get(normalizedPath)?.let { return it }
        val sharedAspect = sharedCache.get(normalizedPath)
        if (sharedAspect != null) {
            cache.put(normalizedPath, sharedAspect)
            val aspectSnapshot = cache.snapshot().toPersistentMap()
            _aspectFlow.value = aspectSnapshot
            return sharedAspect
        }

        val aspectRatio =
            withContext(Dispatchers.IO) {
                decodeAspectRatio(normalizedPath)
            }

        cache.put(normalizedPath, aspectRatio)
        val aspectSnapshot = cache.snapshot().toPersistentMap()
        sharedCache.put(normalizedPath, aspectRatio)
        _aspectFlow.value = aspectSnapshot
        return aspectRatio
    }

    private fun decodeAspectRatio(path: String): Float {
        if (path.startsWith(CONTENT_URI_PREFIX, ignoreCase = true)) {
            return decodeContentUriAspectRatio(path)
        }

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

    private fun decodeContentUriAspectRatio(path: String): Float {
        val resolver = contentResolver ?: return GALLERY_DEFAULT_ASPECT_RATIO
        return runCatching {
            resolver.openInputStream(path.toUri())?.use(::decodeBoundsAspectRatio)
                ?: GALLERY_DEFAULT_ASPECT_RATIO
        }.getOrElse { throwable ->
            Timber.tag(LOG_TAG).w(throwable, "Failed to decode gallery content bounds: $path")
            GALLERY_DEFAULT_ASPECT_RATIO
        }
    }

    private fun decodeBoundsAspectRatio(inputStream: InputStream): Float {
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeStream(inputStream, null, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            GALLERY_DEFAULT_ASPECT_RATIO
        }
    }

    private fun String.toBitmapDecodePath(): String? =
        when {
            startsWith(FILE_URI_PREFIX, ignoreCase = true) ->
                toUri().path

            startsWith(HTTP_URI_PREFIX, ignoreCase = true) ||
                startsWith(HTTPS_URI_PREFIX, ignoreCase = true) ->
                null

            else -> this
        }

    private companion object {
        const val MAX_CACHE_ENTRIES = 256
        const val LOG_TAG = "GalleryImageResolver"
        const val FILE_URI_PREFIX = "file://"
        const val CONTENT_URI_PREFIX = "content://"
        const val HTTP_URI_PREFIX = "http://"
        const val HTTPS_URI_PREFIX = "https://"

        val sharedCache = SynchronizedLruStore<String, Float>(MAX_CACHE_ENTRIES)
    }
}
