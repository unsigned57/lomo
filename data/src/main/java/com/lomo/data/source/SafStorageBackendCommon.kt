package com.lomo.data.source

import kotlinx.coroutines.Dispatchers

internal const val SAF_MARKDOWN_SUFFIX = ".md"
internal const val SAF_TRASH_DIR_NAME = ".trash"
private const val SAF_MAX_IO_PARALLELISM = 4

internal val SAF_IO_DISPATCHER = Dispatchers.IO.limitedParallelism(SAF_MAX_IO_PARALLELISM)

private val SAF_IMAGE_EXTENSIONS =
    setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "bmp",
        "heic",
        "heif",
        "avif",
    )

internal fun safMatchesMarkdownTarget(
    name: String?,
    targetFilename: String?,
): Boolean =
    name != null &&
        name.endsWith(SAF_MARKDOWN_SUFFIX) &&
        (targetFilename == null || name == targetFilename)

internal fun safIsImageFilename(name: String): Boolean {
    val extension = name.substringAfterLast('.', "")
    return extension.isNotBlank() && extension.lowercase() in SAF_IMAGE_EXTENSIONS
}
