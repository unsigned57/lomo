package com.lomo.app.feature.image

import android.app.ActivityManager
import android.content.Context
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineDispatcher
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.coroutines.CoroutineContext

private const val MIB = 1024L * 1024L
private const val MEMORY_CACHE_HEAP_DIVISOR = 4
private const val LOMO_IMAGE_MEMORY_CACHE_BYTES = 32L * MIB
internal const val LOMO_IMAGE_LOADER_IO_PARALLELISM: Int = 4
internal const val LOMO_IMAGE_DISK_CACHE_BYTES: Long = 32L * MIB
internal const val LOMO_IMAGE_DISK_CACHE_DIRECTORY_NAME: String = "image_cache"

internal fun lomoImageMemoryCache(context: Context): MemoryCache {
    val activityManager = checkNotNull(context.getSystemService(ActivityManager::class.java))
    return MemoryCache
        .Builder()
        .maxSizeBytes(
            minOf(activityManager.memoryClass * MIB / MEMORY_CACHE_HEAP_DIVISOR, LOMO_IMAGE_MEMORY_CACHE_BYTES),
        )
        .build()
}

/**
 * Coroutine context that bounds Coil's bitmap decoder parallelism. The
 * previous configuration accidentally limited [interceptorCoroutineContext]
 * instead of the decoder, so heavy fling-time decodes ran unbounded against
 * the IO pool and caused intermittent OOM crashes during cold start.
 *
 * The IO dispatcher is a caller input so the composition root owns the choice.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun lomoImageDecoderCoroutineContext(ioDispatcher: CoroutineDispatcher): CoroutineContext =
    ioDispatcher.limitedParallelism(LOMO_IMAGE_LOADER_IO_PARALLELISM)

/**
 * Coroutine context that bounds Coil's fetcher I/O concurrency. Keeps the
 * total number of streams open during a fast LazyList fling under control
 * even before the decoder limit kicks in.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun lomoImageFetcherCoroutineContext(ioDispatcher: CoroutineDispatcher): CoroutineContext =
    ioDispatcher.limitedParallelism(LOMO_IMAGE_LOADER_IO_PARALLELISM)

/**
 * Retains fetched image data across launches within the app's disk budget.
 */
internal fun lomoImageDiskCache(cacheDir: File): DiskCache = DiskCache
    .Builder()
    .directory(cacheDir.resolve(LOMO_IMAGE_DISK_CACHE_DIRECTORY_NAME).toOkioPath())
    .maxSizeBytes(LOMO_IMAGE_DISK_CACHE_BYTES)
    .build()
