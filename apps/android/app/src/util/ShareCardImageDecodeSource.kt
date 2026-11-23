package com.lomo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.net.toUri
import timber.log.Timber
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class ShareCardImageDecodeSource {
    ContentUri,
    FileUri,
    FilePath,
    Unsupported,
}

internal fun resolveShareCardImageDecodeSource(path: String): ShareCardImageDecodeSource = when {
    path.startsWith(HTTP_PREFIX, ignoreCase = true) ||
        path.startsWith(HTTPS_PREFIX, ignoreCase = true) -> ShareCardImageDecodeSource.Unsupported

    path.startsWith(CONTENT_URI_PREFIX, ignoreCase = true) -> ShareCardImageDecodeSource.ContentUri

    path.startsWith(FILE_URI_PREFIX, ignoreCase = true) -> ShareCardImageDecodeSource.FileUri

    else -> ShareCardImageDecodeSource.FilePath
}

internal fun loadShareImagesByDestination(
    context: Context,
    imageDestinations: List<String>,
    resolvedImagePaths: List<String>,
    targetWidth: Int,
): Map<String, Bitmap> {
    val loadedByDestination = linkedMapOf<String, Bitmap>()
    for ((index, rawDestination) in imageDestinations.withIndex()) {
        if (rawDestination in loadedByDestination) continue
        val resolvedSource = resolvedImagePaths.getOrNull(index) ?: rawDestination
        loadShareImage(context, resolvedSource, targetWidth)?.let { bitmap ->
            loadedByDestination[rawDestination] = bitmap
        }
    }
    return loadedByDestination
}

internal fun loadShareImage(context: Context, path: String, targetWidth: Int): Bitmap? = runCatching {
    val bounds = decodeImageBounds(context, path) ?: return null
    val rawBitmap = decodeBitmap(context, path, bounds.toDecodeOptions(targetWidth)) ?: return null
    val scaled = scaleBitmapToWidth(rawBitmap, targetWidth)
    if (scaled.config == Bitmap.Config.ARGB_8888) {
        scaled
    } else {
        scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
    }
}.getOrElse { throwable ->
    Timber.w(throwable, "Failed to load share image: %s", path)
    null
}

private fun decodeImageBounds(context: Context, path: String): BitmapFactory.Options? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeBitmap(context, path, options)

    return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
}

internal fun BitmapFactory.Options.toDecodeOptions(targetWidth: Int): BitmapFactory.Options =
    BitmapFactory.Options().apply {
        inSampleSize = max(MIN_RENDER_DIMENSION_PX, outWidth / (targetWidth * IMAGE_SAMPLE_WIDTH_DIVISOR))
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

private fun decodeBitmap(context: Context, path: String, options: BitmapFactory.Options): Bitmap? {
    return when (resolveShareCardImageDecodeSource(path)) {
        ShareCardImageDecodeSource.Unsupported -> null

        ShareCardImageDecodeSource.ContentUri -> {
            val uri = path.toUri()
            context.contentResolver.openInputStream(uri)?.use {
                // behavior-contract: blocking-io-ok: share-card decode of one bounded image slot
                BitmapFactory.decodeStream(it, null, options)
            }
        }

        ShareCardImageDecodeSource.FileUri -> {
            val fileUriPath = parseUriPath(path) ?: return null
            // behavior-contract: blocking-io-ok: share-card decode of one bounded image slot
            BitmapFactory.decodeFile(fileUriPath, options)
        }

        ShareCardImageDecodeSource.FilePath ->
            // behavior-contract: blocking-io-ok: share-card decode of one bounded image slot
            BitmapFactory.decodeFile(path, options)
    }
}

private fun scaleBitmapToWidth(rawBitmap: Bitmap, targetWidth: Int): Bitmap {
    val scale = targetWidth.toFloat() / rawBitmap.width
    if (scale in IMAGE_SCALE_SKIP_MIN..IMAGE_SCALE_SKIP_MAX) {
        return rawBitmap
    }

    val scaledHeight = (rawBitmap.height * scale).roundToInt().coerceAtLeast(MIN_RENDER_DIMENSION_PX)
    val scaledBitmap = rawBitmap.scale(targetWidth, scaledHeight, filter = true)
    if (scaledBitmap !== rawBitmap) {
        rawBitmap.recycle()
    }
    return scaledBitmap
}

private fun parseUriPath(value: String): String? =
    // behavior-contract: silent-result-ok: URISyntaxException on malformed input falls back to plain file path
    runCatching {
        URI(value).path
    }.getOrNull()
