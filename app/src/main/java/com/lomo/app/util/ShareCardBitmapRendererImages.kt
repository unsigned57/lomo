package com.lomo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.net.toUri
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun loadShareImages(
    context: Context,
    resolvedImagePaths: List<String>,
    totalImageSlots: Int,
    targetWidth: Int,
): Map<Int, Bitmap> {
    val loadedImages = mutableMapOf<Int, Bitmap>()
    val slotsToLoad = min(totalImageSlots, resolvedImagePaths.size)

    for (index in 0 until slotsToLoad) {
        loadShareImage(context, resolvedImagePaths[index], targetWidth)?.let { loadedImages[index] = it }
    }

    return loadedImages
}

internal fun loadShareImage(
    context: Context,
    path: String,
    targetWidth: Int,
): Bitmap? =
    runCatching {
        val bounds = decodeImageBounds(context, path) ?: return null
        val rawBitmap = decodeBitmap(context, path, bounds.toDecodeOptions(targetWidth)) ?: return null
        scaleBitmapToWidth(rawBitmap, targetWidth)
    }.getOrElse { throwable ->
        Timber.w(throwable, "Failed to load share image: %s", path)
        null
    }

private fun decodeImageBounds(
    context: Context,
    path: String,
): BitmapFactory.Options? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeBitmap(context, path, options)

    return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
}

private fun BitmapFactory.Options.toDecodeOptions(targetWidth: Int): BitmapFactory.Options =
    BitmapFactory.Options().apply {
        inSampleSize = max(MIN_RENDER_DIMENSION_PX, outWidth / (targetWidth * IMAGE_SAMPLE_WIDTH_DIVISOR))
    }

private fun decodeBitmap(
    context: Context,
    path: String,
    options: BitmapFactory.Options,
): Bitmap? {
    val fileUriPath = path.takeIf { it.startsWith(FILE_URI_PREFIX) }?.let(::parseUriPath)

    return when {
        path.isRemotePath() -> loadRemoteBitmap(path, options)
        path.startsWith(CONTENT_URI_PREFIX) -> {
            val uri = path.toUri()
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        fileUriPath != null -> BitmapFactory.decodeFile(fileUriPath, options)
        else -> BitmapFactory.decodeFile(path, options)
    }
}

private fun loadRemoteBitmap(
    path: String,
    options: BitmapFactory.Options,
): Bitmap? {
    val connection =
        (URL(path).openConnection() as HttpURLConnection).apply {
            connectTimeout = REMOTE_IMAGE_TIMEOUT_MS
            readTimeout = REMOTE_IMAGE_TIMEOUT_MS
            instanceFollowRedirects = true
            doInput = true
        }

    return try {
        connection.connect()
        connection.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } finally {
        connection.disconnect()
    }
}

private fun scaleBitmapToWidth(
    rawBitmap: Bitmap,
    targetWidth: Int,
): Bitmap {
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
    runCatching {
        URI(value).path
    }.getOrNull()

private fun String.isRemotePath(): Boolean =
    startsWith(HTTP_PREFIX, ignoreCase = true) || startsWith(HTTPS_PREFIX, ignoreCase = true)
