package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.Memo
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun buildMemoUiImageDependencySignature(
    imageMap: Map<String, Uri>,
): String =
    imageMap
        .map { (key, uri) -> "$key=$uri" }
        .sorted()
        .joinToString(separator = "\n")

internal fun buildMemoListImageDependencySignature(
    memos: List<Memo>,
    imageMap: Map<String, Uri>,
): String =
    memos
        .asSequence()
        .map { memo ->
            buildImageMapDependencySignatureForPaths(
                imagePaths = memo.imageUrls.toSet(),
                imageMap = imageMap,
            )
        }.filter(String::isNotBlank)
        .joinToString(separator = "\n---\n")

internal fun buildImageMapDependencySignatureForPaths(
    imagePaths: Set<String>,
    imageMap: Map<String, Uri>,
): String =
    imagePaths
        .asSequence()
        .flatMap { path -> buildImageMapCandidates(path).asSequence() }
        .distinct()
        .mapNotNull { key -> imageMap[key]?.let { uri -> "$key=$uri" } }
        .sorted()
        .joinToString(separator = "\n")

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
