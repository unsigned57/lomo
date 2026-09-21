package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import java.net.URI

internal const val CONTENT_URI_PREFIX = "content://"
internal const val FILE_URI_PREFIX = "file://"
private const val HTTP_URI_PREFIX = "http://"
private const val HTTPS_URI_PREFIX = "https://"
private const val DATA_IMAGE_PREFIX = "data:image/"
private const val PATH_ROOT_PREFIX = "/"
private const val CURRENT_DIR_PREFIX = "./"
private const val PARENT_DIR_PREFIX = "../"
internal const val QUERY_SEPARATOR = '?'
internal const val FRAGMENT_SEPARATOR = '#'
internal const val PATH_SEPARATOR = '/'
private val MANAGED_IMAGE_FILENAME_REGEX = Regex("""img_\d+\.(png|jpg|jpeg|gif|webp)""")
private const val IMAGE_RESOLVE_CACHE_SIZE = 256

internal data class ResolvedMemoImages(
    val document: MarkdownRenderDocument,
    val imageUrls: ImmutableList<String>,
)

private data class ImageResolveCacheKey(
    val url: String,
    val rootPath: String?,
    val imagePath: String?,
    val mappedUri: String?,
)

internal class MemoUiImageContentResolver {
    private val resolvedPathCache = androidx.collection.LruCache<ImageResolveCacheKey, String>(IMAGE_RESOLVE_CACHE_SIZE)

    fun resolveMemoImages(
        document: MarkdownRenderDocument,
        imageUrls: List<String>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): ResolvedMemoImages {
        val callCache = HashMap<ImageResolveCacheKey, String>()
        fun resolve(url: String): String = resolveCached(url, rootPath, imagePath, imageMap, callCache)
        return ResolvedMemoImages(
            document =
                document.copy(
                    attachmentDestinations = document.attachmentDestinations.map(::resolve),
                    blocks = document.blocks.map { block -> block.resolveImages(::resolve) },
                ),
            imageUrls =
                imageUrls
                    .asSequence()
                    .filterNot(::isAudioAttachmentPath)
                    .map(::resolve)
                    .toList()
                    .toImmutableList(),
        )
    }

    fun resolveRenderDocumentImages(
        document: MarkdownRenderDocument,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): MarkdownRenderDocument =
        resolveMemoImages(
            document = document,
            imageUrls = emptyList(),
            rootPath = rootPath,
            imagePath = imagePath,
            imageMap = imageMap,
        ).document

    fun resolveProjectedImageUrls(
        imageUrls: List<String>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): ImmutableList<String> =
        resolveMemoImages(
            document =
                MarkdownRenderDocument(
                    sourceByteLength = 0uL,
                    plainText = "",
                    tagNames = emptyList(),
                    attachmentDestinations = emptyList(),
                    blocks = emptyList(),
                ),
            imageUrls = imageUrls,
            rootPath = rootPath,
            imagePath = imagePath,
            imageMap = imageMap,
        ).imageUrls

    private fun resolveCached(
        url: String,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
        callCache: MutableMap<ImageResolveCacheKey, String>,
    ): String {
        if (isAudioAttachmentPath(url)) {
            return url
        }
        val mappedUri = findCachedImageUri(normalizeImageUrl(url), imageMap)?.toString()
        val key = ImageResolveCacheKey(url, rootPath, imagePath, mappedUri)
        callCache[key]?.let { return it }
        resolvedPathCache[key]?.let { cached ->
            callCache[key] = cached
            return cached
        }
        val resolved = resolveDestination(url, rootPath, imagePath, imageMap)
        resolvedPathCache.put(key, resolved)
        callCache[key] = resolved
        return resolved
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

    private fun resolveDestination(
        destination: String,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): String {
        if (isAudioAttachmentPath(destination)) return destination
        val resolved =
            resolveImageModel(
                imageUrl = destination,
                isWikiStyle = false,
                rootPath = rootPath,
                imagePath = imagePath,
                imageMap = imageMap,
            )
        return (resolved as? File)?.absolutePath ?: resolved.toString()
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

        return resolveExistingRelativeFile(candidateBasePaths, relativePath)
            ?: resolveRelativeContentUri(candidateBasePaths, relativePath)
            ?: resolveFallbackRelativeFile(candidateBasePaths, relativePath)
            ?: normalizedImageUrl
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

}

private fun resolveRelativeContentUri(
    candidateBasePaths: List<String>,
    relativePath: String,
): String? =
    candidateBasePaths.firstNotNullOfOrNull { basePath ->
        if (basePath.startsWith(CONTENT_URI_PREFIX)) {
            // behavior-contract: silent-result-ok: non-tree URI or malformed URI syntax falls back to next candidate
            runCatching {
                val rootUri = Uri.parse(basePath)
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(rootUri)
                    ?: return@firstNotNullOfOrNull null
                val normalized = normalizeRelativePath(relativePath, removeParentSegments = false)
                val docId = if (treeDocId.endsWith('/')) "$treeDocId$normalized" else "$treeDocId/$normalized"
                android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
            }.getOrNull()
        } else {
            null
        }
    }

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

internal fun normalizeImageUrl(raw: String): String =
    raw
        .trim()
        .removeSurrounding("<", ">")
        .replace('\\', PATH_SEPARATOR)

private fun isAbsoluteOrRemoteImageUrl(value: String): Boolean {
    val lower = value.lowercase(java.util.Locale.ROOT)
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

internal fun normalizeRelativePath(
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

internal fun parseUriPath(value: String): String? =
    // behavior-contract: silent-result-ok: URISyntaxException on malformed input means "no path component"
    runCatching {
        URI(value).path
    }.getOrNull()

private fun looksLikeManagedImageFilename(path: String): Boolean {
    val candidate = path.substringAfterLast(PATH_SEPARATOR).lowercase(java.util.Locale.ROOT)
    return candidate.matches(MANAGED_IMAGE_FILENAME_REGEX)
}
