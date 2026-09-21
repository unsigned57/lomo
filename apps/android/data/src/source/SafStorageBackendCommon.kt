package com.lomo.data.source

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher

internal const val SAF_MARKDOWN_SUFFIX = ".md"
internal const val SAF_TRASH_DIR_NAME = ".trash"
private const val SAF_MAX_IO_PARALLELISM = 4

internal fun safIoDispatcher(dispatcherProvider: DispatcherProvider): CoroutineDispatcher =
    dispatcherProvider.io.limitedParallelism(SAF_MAX_IO_PARALLELISM)

internal fun safMatchesMarkdownTarget(
    name: String?,
    targetFilename: String?,
): Boolean =
    name != null &&
        name.endsWith(SAF_MARKDOWN_SUFFIX) &&
        (targetFilename == null || name == targetFilename)

internal fun safIsImageFilename(name: String): Boolean {
    return MediaFileExtensions.hasImageExtension(name)
}
