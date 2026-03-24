package com.lomo.app.feature.main

import android.net.Uri
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val CONTENT_URI_PREFIX = "content://"
private const val FILE_URI_PREFIX = "file://"
private const val HTTP_URI_PREFIX = "http://"
private const val HTTPS_URI_PREFIX = "https://"
private const val DATA_IMAGE_PREFIX = "data:image/"
private const val PATH_ROOT_PREFIX = "/"
private const val CURRENT_DIR_PREFIX = "./"
private const val PARENT_DIR_PREFIX = "../"
private const val QUERY_SEPARATOR = '?'
private const val FRAGMENT_SEPARATOR = '#'
private const val PATH_SEPARATOR = '/'
private const val WIKI_IMAGE_ALT_SEPARATOR = '|'

private val WIKI_IMAGE_REGEX = Regex("""!\[\[(.*?)\]\]""")
private val MARKDOWN_IMAGE_REGEX = Regex("""!\[(.*?)\]\((.*?)\)""")
private val EXTRACT_IMAGE_URL_REGEX = Regex("""!\[.*?\]\((.*?)\)""")
private val MANAGED_IMAGE_FILENAME_REGEX = Regex("""img_\d+\.(png|jpg|jpeg|gif|webp)""")
private val AUDIO_EXTENSIONS = setOf(".m4a", ".mp3", ".aac", ".wav")

internal class MemoUiImageContentResolver {
    fun buildProcessedContent(
        content: String,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): String {
        val wikiResolvedContent =
            WIKI_IMAGE_REGEX.replace(content) { match ->
                val path = match.groupValues[1].substringBefore(WIKI_IMAGE_ALT_SEPARATOR).trim()
                val resolved =
                    resolveImageModel(
                        imageUrl = path,
                        isWikiStyle = true,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                    )
                val finalUrl = (resolved as? File)?.absolutePath ?: resolved.toString()
                "![]($finalUrl)"
            }

        return MARKDOWN_IMAGE_REGEX.replace(wikiResolvedContent) { match ->
            val alt = match.groupValues[1]
            val path = match.groupValues[2]

            if (AUDIO_EXTENSIONS.any { path.lowercase().endsWith(it) }) {
                match.value
            } else {
                val resolved =
                    resolveImageModel(
                        imageUrl = path,
                        isWikiStyle = false,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                    )
                val finalUrl = (resolved as? File)?.absolutePath ?: resolved.toString()
                "![$alt]($finalUrl)"
            }
        }
    }

    fun extractImageUrls(content: String): ImmutableList<String> {
        val imageUrls = mutableListOf<String>()
        EXTRACT_IMAGE_URL_REGEX.findAll(content).forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank()) {
                imageUrls.add(url)
            }
        }
        return imageUrls.toImmutableList()
    }

    private fun resolveImageModel(
        imageUrl: String,
        isWikiStyle: Boolean,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): Any {
        val normalizedImageUrl = normalizeImageUrl(imageUrl)
        resolveDirectImageModel(normalizedImageUrl, imageMap)?.let { return it }

        return resolveRelativeImageModel(
            normalizedImageUrl = normalizedImageUrl,
            isWikiStyle = isWikiStyle,
            rootPath = rootPath,
            imagePath = imagePath,
        )
    }

    private fun resolveDirectImageModel(
        normalizedImageUrl: String,
        imageMap: Map<String, Uri>,
    ): Any? =
        findCachedImageUri(normalizedImageUrl, imageMap)
            ?: normalizedImageUrl.takeIf(::isAbsoluteOrRemoteImageUrl)

    private fun resolveRelativeImageModel(
        normalizedImageUrl: String,
        isWikiStyle: Boolean,
        rootPath: String?,
        imagePath: String?,
    ): Any {
        val relativePath = normalizeRelativePath(normalizedImageUrl, removeParentSegments = false)
        val candidateBasePaths = buildCandidateBasePaths(isWikiStyle, rootPath, imagePath, relativePath)
        val contentUriFallback =
            normalizedImageUrl.takeIf { containsContentUriBase(candidateBasePaths) }

        return resolveExistingRelativeFile(candidateBasePaths, relativePath)
            ?: contentUriFallback
            ?: resolveFallbackRelativeFile(candidateBasePaths, relativePath)
            ?: normalizedImageUrl
    }

    private fun containsContentUriBase(candidateBasePaths: List<String>): Boolean =
        candidateBasePaths.any { basePath -> basePath.startsWith(CONTENT_URI_PREFIX) }

    private fun resolveExistingRelativeFile(
        candidateBasePaths: List<String>,
        relativePath: String,
    ): File? =
        candidateBasePaths.firstNotNullOfOrNull { basePath ->
            if (basePath.startsWith(CONTENT_URI_PREFIX)) {
                null
            } else {
                resolveRelativeFile(
                    basePath = normalizeBasePath(basePath),
                    relativePath = relativePath,
                ).takeIf(File::exists)
            }
        }

    private fun resolveFallbackRelativeFile(
        candidateBasePaths: List<String>,
        relativePath: String,
    ): File? =
        candidateBasePaths
            .firstOrNull()
            ?.takeUnless { it.startsWith(CONTENT_URI_PREFIX) }
            ?.let { basePath ->
                resolveRelativeFile(
                    basePath = normalizeBasePath(basePath),
                    relativePath = relativePath,
                )
            }

    private fun buildCandidateBasePaths(
        isWikiStyle: Boolean,
        rootPath: String?,
        imagePath: String?,
        relativePath: String,
    ): List<String> {
        val candidates = LinkedHashSet<String>()

        fun addBasePath(path: String?) {
            val value = path?.trim().orEmpty()
            if (value.isNotEmpty()) {
                candidates += value
            }
        }

        if (isWikiStyle) {
            addBasePath(imagePath)
            addBasePath(rootPath)
        } else if (looksLikeManagedImageFilename(relativePath)) {
            addBasePath(imagePath)
            addBasePath(rootPath)
        } else {
            addBasePath(rootPath)
            addBasePath(imagePath)
        }
        return candidates.toList()
    }

    private fun findCachedImageUri(
        imageUrl: String,
        imageMap: Map<String, Uri>,
    ): Uri? {
        if (imageMap.isEmpty()) return null
        val candidates = buildImageMapCandidates(imageUrl)
        return candidates.firstNotNullOfOrNull { key -> imageMap[key] }
    }

    private fun buildImageMapCandidates(imageUrl: String): List<String> {
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
}

private fun containsContentUriBase(candidateBasePaths: List<String>): Boolean =
    candidateBasePaths.any { basePath -> basePath.startsWith(CONTENT_URI_PREFIX) }

private fun resolveExistingRelativeFile(
    candidateBasePaths: List<String>,
    relativePath: String,
): File? =
    candidateBasePaths.firstNotNullOfOrNull { basePath ->
        if (basePath.startsWith(CONTENT_URI_PREFIX)) {
            null
        } else {
            resolveRelativeFile(
                basePath = normalizeBasePath(basePath),
                relativePath = relativePath,
            ).takeIf(File::exists)
        }
    }

private fun resolveFallbackRelativeFile(
    candidateBasePaths: List<String>,
    relativePath: String,
): File? =
    candidateBasePaths
        .firstOrNull()
        ?.takeUnless { it.startsWith(CONTENT_URI_PREFIX) }
        ?.let { basePath ->
            resolveRelativeFile(
                basePath = normalizeBasePath(basePath),
                relativePath = relativePath,
            )
        }

private fun normalizeImageUrl(raw: String): String =
    raw
        .trim()
        .removeSurrounding("<", ">")
        .replace('\\', PATH_SEPARATOR)

private fun isAbsoluteOrRemoteImageUrl(value: String): Boolean {
    val lower = value.lowercase()
    return lower.startsWith(PATH_ROOT_PREFIX) ||
        lower.startsWith(CONTENT_URI_PREFIX) ||
        lower.startsWith(FILE_URI_PREFIX) ||
        lower.startsWith(HTTP_URI_PREFIX) ||
        lower.startsWith(HTTPS_URI_PREFIX) ||
        lower.startsWith(DATA_IMAGE_PREFIX)
}

private fun normalizeBasePath(basePath: String): String =
    if (basePath.startsWith(FILE_URI_PREFIX)) {
        parseUriPath(basePath) ?: basePath
    } else {
        basePath
    }

private fun normalizeRelativePath(
    path: String,
    removeParentSegments: Boolean,
): String {
    var result = path
    while (result.startsWith(CURRENT_DIR_PREFIX)) {
        result = result.removePrefix(CURRENT_DIR_PREFIX)
    }
    if (removeParentSegments) {
        while (result.startsWith(PARENT_DIR_PREFIX)) {
            result = result.removePrefix(PARENT_DIR_PREFIX)
        }
        result = result.trimStart(PATH_SEPARATOR)
    }
    return result
}

private fun resolveRelativeFile(
    basePath: String,
    relativePath: String,
): File {
    var base = File(basePath)
    var path = relativePath

    while (path.startsWith(PARENT_DIR_PREFIX)) {
        base = base.parentFile ?: base
        path = path.removePrefix(PARENT_DIR_PREFIX)
    }
    path = normalizeRelativePath(path, removeParentSegments = false)
    return File(base, path)
}

private fun parseUriPath(value: String): String? =
    runCatching {
        URI(value).path
    }.getOrNull()

private fun looksLikeManagedImageFilename(path: String): Boolean {
    val candidate = path.substringAfterLast(PATH_SEPARATOR).lowercase()
    return candidate.matches(MANAGED_IMAGE_FILENAME_REGEX)
}
