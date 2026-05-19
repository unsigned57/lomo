package com.lomo.app.feature.image

import coil3.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.coroutines.CoroutineContext

internal const val LOMO_IMAGE_LOADER_MEMORY_CACHE_PERCENT: Double = 0.25
internal const val LOMO_IMAGE_LOADER_IO_PARALLELISM: Int = 4
internal const val LOMO_IMAGE_DISK_CACHE_BYTES: Long = 64L * 1024L * 1024L
internal const val LOMO_IMAGE_DISK_CACHE_DIRECTORY_NAME: String = "image_cache"

/**
 * Coroutine context that bounds Coil's bitmap decoder parallelism. The
 * previous configuration accidentally limited [interceptorCoroutineContext]
 * instead of the decoder, so heavy fling-time decodes ran unbounded against
 * the IO pool and caused intermittent OOM crashes during cold start.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val lomoImageDecoderCoroutineContext: CoroutineContext =
    Dispatchers.IO.limitedParallelism(LOMO_IMAGE_LOADER_IO_PARALLELISM)

/**
 * Coroutine context that bounds Coil's fetcher I/O concurrency. Keeps the
 * total number of streams open during a fast LazyList fling under control
 * even before the decoder limit kicks in.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val lomoImageFetcherCoroutineContext: CoroutineContext =
    Dispatchers.IO.limitedParallelism(LOMO_IMAGE_LOADER_IO_PARALLELISM)

/**
 * Builds the on-disk image cache used by Coil. Re-enabling the disk cache
 * (Coil previously had [CachePolicy.DISABLED]) means a cold-start re-entry
 * into the list can hit the already-downsampled bytes Coil wrote on the
 * previous run, avoiding a wave of full bitmap decodes against the source
 * file. Sized at [LOMO_IMAGE_DISK_CACHE_BYTES] (64 MB) so the cache itself
 * stays bounded and predictable.
 */
internal fun lomoImageDiskCache(cacheDir: File): DiskCache =
    DiskCache
        .Builder()
        .directory(cacheDir.resolve(LOMO_IMAGE_DISK_CACHE_DIRECTORY_NAME).toOkioPath())
        .maxSizeBytes(LOMO_IMAGE_DISK_CACHE_BYTES)
        .build()
