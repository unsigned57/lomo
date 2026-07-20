package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
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
internal class MemoUiImageContentResolver {
    fun resolveRenderDocumentImages(
        document: MarkdownRenderDocument,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): MarkdownRenderDocument =
        document.copy(
            attachmentDestinations =
                document.attachmentDestinations.map { destination ->
                    resolveDestination(destination, rootPath, imagePath, imageMap)
                },
            blocks = document.blocks.map { block -> block.resolveImages(rootPath, imagePath, imageMap) },
        )

    fun resolveProjectedImageUrls(
        imageUrls: List<String>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): ImmutableList<String> =
        imageUrls
            .asSequence()
            .filterNot(::isAudioAttachmentPath)
            .map { imageUrl ->
                val resolved =
                    resolveImageModel(
                        imageUrl = imageUrl,
                        isWikiStyle = false,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                    )
                ((resolved as? File)?.absolutePath ?: resolved.toString())
            }.toList()
            .toImmutableList()

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

    private fun MarkdownRenderBlock.resolveImages(
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): MarkdownRenderBlock =
        when (this) {
            is MarkdownRenderBlock.Paragraph -> copy(inlines = inlines.resolveImages(rootPath, imagePath, imageMap))
            is MarkdownRenderBlock.Heading -> copy(inlines = inlines.resolveImages(rootPath, imagePath, imageMap))
            is MarkdownRenderBlock.BlockQuote ->
                copy(blocks = blocks.map { it.resolveImages(rootPath, imagePath, imageMap) })
            is MarkdownRenderBlock.ListBlock ->
                copy(
                    items =
                        items.map { item ->
                            item.copy(blocks = item.blocks.map { it.resolveImages(rootPath, imagePath, imageMap) })
                        },
                )
            is MarkdownRenderBlock.Table ->
                copy(
                    header = header.map { cell -> cell.copy(inlines = cell.inlines.resolveImages(rootPath, imagePath, imageMap)) },
                    rows =
                        rows.map { row ->
                            row.map { cell -> cell.copy(inlines = cell.inlines.resolveImages(rootPath, imagePath, imageMap)) }
                        },
                )
            is MarkdownRenderBlock.CodeBlock,
            is MarkdownRenderBlock.ThematicBreak,
            is MarkdownRenderBlock.HtmlBlock,
            -> this
        }

    private fun List<MarkdownRenderInline>.resolveImages(
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): List<MarkdownRenderInline> =
        map { inline ->
            when (inline) {
                is MarkdownRenderInline.Strong ->
                    inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                is MarkdownRenderInline.Emphasis ->
                    inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                is MarkdownRenderInline.Strikethrough ->
                    inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                is MarkdownRenderInline.Highlight ->
                    inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                is MarkdownRenderInline.Link -> inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                is MarkdownRenderInline.Image ->
                    inline.copy(destination = resolveDestination(inline.destination, rootPath, imagePath, imageMap))
                is MarkdownRenderInline.WikiReference ->
                    inline.copy(inlines = inline.inlines.resolveImages(rootPath, imagePath, imageMap))
                else -> inline
            }
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
