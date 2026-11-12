package com.lomo.app.feature.main

import java.io.File
import java.net.URI

/**
 * A dedicated image root owns images/ paths and selected bare names.
 * Without a selection, bare names pool workspace claims and qualified paths join the workspace.
 */
internal fun fileProbeTargets(
    canonical: String,
    isImageRootRelative: Boolean,
    rootPath: String?,
    imagePath: String?,
): List<File> {
    val targets = LinkedHashSet<File>()
    val root = usableFileBase(rootPath)

    if (isImageRootRelative && !imagePath.isNullOrBlank()) {
        usableFileBase(imagePath)?.let { targets += File(it, canonical) }
    } else if (isImageRootRelative) {
        root?.let { targets += File(it, canonical) }
        for (dir in MANAGED_ATTACHMENT_ROOTS) {
            val managedDir = root?.let { File(it, dir) } ?: continue
            managedDir
                .walkTopDown()
                .onEnter { entry -> entry == managedDir || entry.name !in MEDIA_LIFECYCLE_LANE_NAMES }
                .filter { entry -> entry.name == canonical }
                .forEach { entry -> targets += entry }
        }
    } else {
        root?.let { targets += File(it, canonical) }
    }
    return targets.toList()
}

/** Media lifecycle lanes the workspace owner enumerates out of attachment claims. */
private val MEDIA_LIFECYCLE_LANE_NAMES =
    setOf(".lomo-media-stage", ".lomo-media-trash", ".lomo-media-delete-intents")

/** A non-content base path reduced to a usable `File` root, else null. */
private fun usableFileBase(basePath: String?): File? {
    val base = basePath?.trim().orEmpty()
    if (base.isEmpty() || base.startsWith(CONTENT_URI_PREFIX)) {
        return null
    }
    return File(normalizeBasePath(base))
}

internal fun resolveFallbackRelativeFile(
    canonical: String,
    isImageRootRelative: Boolean,
    rootPath: String?,
    imagePath: String?,
): File? = fileBasesInOrder(isImageRootRelative, rootPath, imagePath)
    .firstOrNull()
    ?.let { base -> File(normalizeBasePath(base), canonical) }

/** Selects the physical image root only for paths routed to that mount. */
private fun fileBasesInOrder(isImageRootRelative: Boolean, rootPath: String?, imagePath: String?): List<String> {
    val ordered = LinkedHashSet<String>()

    fun add(base: String?) {
        val value = base?.trim().orEmpty()
        if (value.isNotEmpty() && !value.startsWith(CONTENT_URI_PREFIX)) {
            ordered += value
        }
    }

    if (isImageRootRelative && !imagePath.isNullOrBlank()) add(imagePath) else add(rootPath)
    return ordered.toList()
}

private fun normalizeBasePath(basePath: String): String = if (basePath.startsWith(FILE_URI_PREFIX)) {
    parseUriPath(basePath) ?: basePath
} else {
    basePath
}

internal fun parseUriPath(value: String): String? =
    // behavior-contract: silent-result-ok: URISyntaxException on malformed input means "no path component"
    runCatching {
        URI(value).path
    }.getOrNull()
