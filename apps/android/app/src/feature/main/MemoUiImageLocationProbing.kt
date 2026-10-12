package com.lomo.app.feature.main

import android.net.Uri
import java.io.File
import java.net.URI

private val MANAGED_IMAGE_FILENAME_REGEX = Regex("""img_\d+\.(png|jpg|jpeg|gif|webp)""")

/**
 * Verified filesystem probe targets: every directory surface the destination
 * may legitimately occupy. A bare name probes the root, the configured image
 * root, and every file named like it at any depth under the managed attachment
 * roots; a qualified path probes it under each configured base. Content-URI
 * bases skip — SAF trees cannot be stat'ed here.
 */
internal fun fileProbeTargets(
    canonical: String,
    isBareName: Boolean,
    rootPath: String?,
    imagePath: String?,
): List<File> {
    val targets = LinkedHashSet<File>()
    val root = usableFileBase(rootPath)

    if (isBareName) {
        root?.let { targets += File(it, canonical) }
        for (dir in MANAGED_ATTACHMENT_ROOTS) {
            val managedDir = root?.let { File(it, dir) } ?: continue
            managedDir
                .walkTopDown()
                .onEnter { entry -> entry == managedDir || entry.name !in MEDIA_LIFECYCLE_LANE_NAMES }
                .filter { entry -> entry.name == canonical }
                .forEach { entry -> targets += entry }
        }
        usableFileBase(imagePath)?.let { targets += File(it, canonical) }
    } else {
        root?.let { targets += File(it, canonical) }
        usableFileBase(imagePath)?.let { targets += File(it, canonical) }
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

/**
 * Content-URI bases in deterministic order. Bare names keep the historical
 * convention — managed `img_\d+` names probe the managed image root first,
 * other bare names the workspace root first — because docId joins cannot be
 * existence-verified at this layer; the first join is a guess that fails closed
 * in Coil when wrong, never a wrong-directory load. Qualified destinations are
 * identities: workspace root first.
 */
private fun contentUriBases(
    canonical: String,
    isBareName: Boolean,
    rootPath: String?,
    imagePath: String?,
): List<String> {
    val ordered = LinkedHashSet<String>()

    fun add(base: String?) {
        val value = base?.trim().orEmpty()
        if (value.isNotEmpty() && value.startsWith(CONTENT_URI_PREFIX)) {
            ordered += value
        }
    }

    if (isBareName && looksLikeManagedImageFilename(canonical)) {
        add(imagePath)
        add(rootPath)
    } else {
        add(rootPath)
        add(imagePath)
    }
    return ordered.toList()
}

internal fun resolveRelativeContentUri(
    canonical: String,
    isBareName: Boolean,
    rootPath: String?,
    imagePath: String?,
): String? =
    contentUriBases(canonical, isBareName, rootPath, imagePath).firstNotNullOfOrNull { basePath ->
        // behavior-contract: silent-result-ok: non-tree URI or malformed URI syntax falls back to next candidate
        runCatching {
            val rootUri = Uri.parse(basePath)
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(rootUri)
                ?: return@firstNotNullOfOrNull null
            val docId = if (treeDocId.endsWith('/')) "$treeDocId$canonical" else "$treeDocId/$canonical"
            android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
        }.getOrNull()
    }

internal fun resolveFallbackRelativeFile(
    canonical: String,
    isBareName: Boolean,
    rootPath: String?,
    imagePath: String?,
): File? =
    fileBasesInOrder(canonical, isBareName, rootPath, imagePath)
        .firstOrNull()
        ?.let { base -> File(normalizeBasePath(base), canonical) }

/** All configured bases in the same deterministic order as [contentUriBases]. */
private fun fileBasesInOrder(
    canonical: String,
    isBareName: Boolean,
    rootPath: String?,
    imagePath: String?,
): List<String> {
    val ordered = LinkedHashSet<String>()

    fun add(base: String?) {
        val value = base?.trim().orEmpty()
        if (value.isNotEmpty() && !value.startsWith(CONTENT_URI_PREFIX)) {
            ordered += value
        }
    }

    if (isBareName && looksLikeManagedImageFilename(canonical)) {
        add(imagePath)
        add(rootPath)
    } else {
        add(rootPath)
        add(imagePath)
    }
    return ordered.toList()
}

private fun normalizeBasePath(basePath: String): String =
    if (basePath.startsWith(FILE_URI_PREFIX)) {
        parseUriPath(basePath) ?: basePath
    } else {
        basePath
    }

internal fun parseUriPath(value: String): String? =
    // behavior-contract: silent-result-ok: URISyntaxException on malformed input means "no path component"
    runCatching {
        URI(value).path
    }.getOrNull()

private fun looksLikeManagedImageFilename(path: String): Boolean {
    val candidate = path.substringAfterLast(PATH_SEPARATOR).lowercase(java.util.Locale.ROOT)
    return candidate.matches(MANAGED_IMAGE_FILENAME_REGEX)
}
