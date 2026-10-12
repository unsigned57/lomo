package com.lomo.data.source

import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * Walks a relative path (segments separated by `/`), creating any missing directories on the way
 * and creating the leaf file with [leafMimeType] if it does not exist. Returns the existing or
 * freshly-created leaf DocumentFile, or throws if creation fails at any step.
 */
internal fun safResolveOrCreateRelative(
    root: DocumentFile,
    relativePath: String,
    leafMimeType: String,
): DocumentFile {
    val segments = relativePath.split('/').filter(String::isNotEmpty)
    require(segments.isNotEmpty()) { "relativePath must not be empty" }
    var current = root
    for (index in 0 until segments.size - 1) {
        val segment = segments[index]
        current = current.findFile(segment)
            ?: current.createDirectory(segment)
            ?: throw IOException("Failed to create SAF directory segment '$segment' for $relativePath")
    }
    val leafName = segments.last()
    return current.findFile(leafName)
        ?: current.createFile(leafMimeType, leafName)
        ?: throw IOException("Failed to create SAF leaf '$leafName' for $relativePath")
}
