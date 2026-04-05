package com.lomo.app.feature.main

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun buildMemoUiImageDependencySignature(
    content: String,
    imageMap: Map<String, Uri>,
): String {
    if (!containsInlineMediaMarkup(content) || imageMap.isEmpty()) {
        return ""
    }
    return collectReferencedImageUrls(content)
        .flatMap(::buildImageMapCandidates)
        .distinct()
        .mapNotNull { key -> imageMap[key]?.let { uri -> "$key=$uri" } }
        .sorted()
        .joinToString(separator = "\n")
}

internal fun buildImageMapCandidates(imageUrl: String): List<String> {
    val candidates = LinkedHashSet<String>()

    fun decodeUrlComponent(value: String): String =
        runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)

    fun addCandidate(raw: String?) {
        val value = raw?.trim().orEmpty()
        if (value.isNotEmpty()) {
            candidates.add(value)
        }
    }

    val normalized = normalizeImageUrl(imageUrl)
    if (normalized.isBlank()) {
        return emptyList()
    }

    addCandidate(normalized)
    addCandidate(decodeUrlComponent(normalized))
    val noQuery = normalized.substringBefore(QUERY_SEPARATOR).substringBefore(FRAGMENT_SEPARATOR)
    addCandidate(noQuery)
    addCandidate(decodeUrlComponent(noQuery))
    val stripped = normalizeRelativePath(noQuery, removeParentSegments = true)
    addCandidate(stripped)
    val basename = stripped.substringAfterLast(PATH_SEPARATOR)
    addCandidate(basename)
    addCandidate(decodeUrlComponent(basename))

    if (normalized.startsWith(FILE_URI_PREFIX) || normalized.startsWith(CONTENT_URI_PREFIX)) {
        addCandidate(parseUriPath(normalized)?.substringAfterLast(PATH_SEPARATOR))
    }

    return candidates.toList()
}

private fun collectReferencedImageUrls(content: String): List<String> {
    val referencedUrls = LinkedHashSet<String>()
    WIKI_IMAGE_REGEX.findAll(content).forEach { match ->
        val url = match.groupValues[1].substringBefore(WIKI_IMAGE_ALT_SEPARATOR).trim()
        if (url.isNotBlank()) {
            referencedUrls += url
        }
    }
    MARKDOWN_IMAGE_REGEX.findAll(content).forEach { match ->
        val url = match.groupValues[2].trim()
        if (url.isNotBlank()) {
            referencedUrls += url
        }
    }
    return referencedUrls.toList()
}
