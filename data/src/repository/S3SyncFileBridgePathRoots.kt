package com.lomo.data.repository

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun relativePathFrom(
    root: File,
    child: File,
): String? {
    val rootPath = normalizePath(root)
    val childPath = normalizePath(child)
    return when {
        childPath == rootPath -> ""
        childPath.startsWith("$rootPath/") -> childPath.removePrefix("$rootPath/")
        else -> null
    }
}

private fun normalizePath(file: File): String =
    file.absoluteFile.normalize().path.replace(File.separatorChar, '/').trimEnd('/')

internal fun isAncestor(
    ancestor: File,
    child: File,
): Boolean {
    val ancestorPath = normalizePath(ancestor)
    val childPath = normalizePath(child)
    return childPath == ancestorPath || childPath.startsWith("$ancestorPath/")
}

internal fun effectiveConfiguredLocation(
    directory: String?,
    uri: String?,
): String? = directory?.trim()?.takeIf(String::isNotBlank) ?: uri?.trim()?.takeIf(String::isNotBlank)

internal fun relativeConfiguredLocation(
    rootLocation: String,
    candidateLocation: String?,
): String? {
    val normalizedRoot = normalizeConfiguredLocation(rootLocation) ?: return null
    val normalizedCandidate = candidateLocation?.let(::normalizeConfiguredLocation) ?: return null
    return when {
        normalizedCandidate == normalizedRoot -> ""
        normalizedCandidate.startsWith("$normalizedRoot/") -> normalizedCandidate.removePrefix("$normalizedRoot/")
        else -> null
    }
}

private fun normalizeConfiguredLocation(pathOrUri: String): String? {
    val raw = pathOrUri.trim().takeIf(String::isNotBlank) ?: return null
    return if (isContentUriRoot(raw)) {
        val decoded = URLDecoder.decode(raw.substringAfterLast('/'), StandardCharsets.UTF_8.name())
        decoded.substringAfter(':', decoded).replace('\\', '/').trim('/').ifBlank { "" }
    } else {
        File(raw).absoluteFile.normalize().path.replace(File.separatorChar, '/').trimEnd('/')
    }
}
