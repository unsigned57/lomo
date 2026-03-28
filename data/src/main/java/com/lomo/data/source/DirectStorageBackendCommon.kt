package com.lomo.data.source

import java.io.File

internal fun directTrashDir(rootDir: File): File = File(rootDir, DIRECT_TRASH_DIR_NAME)

internal fun directEnsureRootExists(rootDir: File) {
    if (!rootDir.exists()) {
        rootDir.mkdirs()
    }
}

internal fun directEnsureTrashExists(rootDir: File) {
    val trashDir = directTrashDir(rootDir)
    if (!trashDir.exists()) {
        trashDir.mkdirs()
    }
}

internal fun directIsImageFilename(name: String): Boolean {
    val extension = name.substringAfterLast('.', "")
    return extension.isNotBlank() && extension.lowercase(java.util.Locale.ROOT) in DIRECT_IMAGE_EXTENSIONS
}

internal const val DIRECT_MARKDOWN_SUFFIX = ".md"
private const val DIRECT_TRASH_DIR_NAME = ".trash"

private val DIRECT_IMAGE_EXTENSIONS =
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
