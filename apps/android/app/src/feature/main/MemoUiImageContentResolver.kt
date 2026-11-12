package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.io.File

internal const val CONTENT_URI_PREFIX = "content://"
internal const val FILE_URI_PREFIX = "file://"
private const val HTTP_URI_PREFIX = "http://"
private const val HTTPS_URI_PREFIX = "https://"
private const val DATA_IMAGE_PREFIX = "data:image/"
private const val PATH_ROOT_PREFIX = "/"
internal const val QUERY_SEPARATOR = '?'
internal const val FRAGMENT_SEPARATOR = '#'
internal const val PATH_SEPARATOR = '/'
private const val IMAGE_RESOLVE_CACHE_SIZE = 256

internal data class ResolvedMemoImages(val document: MarkdownRenderDocument, val imageUrls: ImmutableList<String>)

private data class ImageResolveCacheKey(
    val url: String,
    val rootPath: String?,
    val imagePath: String?,
    val mappedSignature: String?,
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
        fun resolveImageDestination(url: String): String = resolveCached(url, rootPath, imagePath, imageMap, callCache)

        // Every non-audio destination resolves into the image-load lane; authenticity is
        // judged fail-closed inside Coil. Audio keeps the raw workspace path its player needs.
        fun resolveImageDestination(image: MarkdownRenderInline.Image): String =
            if (image.mediaClass != MediaClass.AUDIO) {
                resolveImageDestination(image.destination)
            } else {
                image.destination
            }

        return ResolvedMemoImages(
            document =
            document.copy(
                attachments =
                document.attachments.map { attachment ->
                    if (attachment.mediaClass != MediaClass.AUDIO) {
                        attachment.copy(destination = resolveImageDestination(attachment.destination))
                    } else {
                        attachment
                    }
                },
                blocks = document.blocks.map { block -> block.resolveImages(::resolveImageDestination) },
            ),
            imageUrls =
            imageUrls
                .map(::resolveImageDestination)
                .toImmutableList(),
        )
    }

    fun resolveRenderDocumentImages(
        document: MarkdownRenderDocument,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): MarkdownRenderDocument = resolveMemoImages(
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
    ): ImmutableList<String> = resolveMemoImages(
        document =
        MarkdownRenderDocument(
            sourceByteLength = 0uL,
            plainText = "",
            tagNames = emptyList(),
            attachments = emptyList(),
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
        val mappedSignature = imageMapLookupSignature(url, imageMap)
        val key = ImageResolveCacheKey(url, rootPath, imagePath, mappedSignature)
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

    private fun resolveDestination(
        destination: String,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): String {
        val resolved = resolveImageModel(
            imageUrl = destination,
            rootPath = rootPath,
            imagePath = imagePath,
            imageMap = imageMap,
        )
        return (resolved as? File)?.absolutePath ?: resolved.toString()
    }

    /**
     * Resolves one normalized destination under the workspace directory law:
     * images/ uses a dedicated image selection; other qualified paths retain workspace identity.
     * A shared notes/image root keeps the workspace namespace instead of mounting it twice.
     */
    private fun resolveImageModel(
        imageUrl: String,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): Any {
        val normalizedImageUrl = normalizeImageUrl(imageUrl)
        if (isAbsoluteOrRemoteImageUrl(normalizedImageUrl)) {
            return normalizedImageUrl
        }
        // Canonicalize exactly like the workspace owner: `.`/`..` segments fold
        // and a root escape rejects — `../x.png` is not a workspace file.
        val canonical =
            canonicalizeAttachmentPath(normalizedImageUrl) ?: return normalizedImageUrl
        val isBareName = PATH_SEPARATOR !in canonical
        val selectedImagePath = imagePath?.takeUnless { it == rootPath }
        val isMountedImage = !selectedImagePath.isNullOrBlank() && canonical.startsWith("images/")
        val probePath = if (isMountedImage) canonical.removePrefix("images/") else canonical
        val probeInImageRoot = isBareName || isMountedImage

        // Pooled verifiable candidates: image-map hits plus existing files. Two
        // distinct locations claiming one basename fail closed — the first
        // candidate is never a default.
        val pooled = LinkedHashMap<String, Any>()
        val lookupKeys = imageMapLookupKeys(normalizedImageUrl, imageMap.keys)
        val selectedDirectoryKeys =
            if (isBareName && !selectedImagePath.isNullOrBlank()) {
                lookupKeys.filter { PATH_SEPARATOR !in it }
            } else {
                lookupKeys
            }
        for (key in selectedDirectoryKeys) {
            imageMap[key]?.let { uri ->
                pooled.putIfAbsent(locationIdentity(uri.toString()), uri)
            }
        }
        for (probe in fileProbeTargets(probePath, probeInImageRoot, rootPath, selectedImagePath)) {
            if (probe.isFile) {
                pooled.putIfAbsent(locationIdentity(probe.absolutePath), probe)
            }
        }
        when (pooled.size) {
            0 -> Unit
            1 -> return pooled.values.single()
            else -> return normalizedImageUrl
        }

        // SAF locations come only from the provider-backed image map. A document ID is
        // opaque: joining a path onto a tree ID cannot establish a readable location.
        return resolveFallbackRelativeFile(probePath, probeInImageRoot, rootPath, selectedImagePath)
            ?: normalizedImageUrl
    }
}

/**
 * Dedupe key across `file://` URIs, plain paths and content URIs. The
 * `file://` scheme and any query/fragment (the `lomo-cid` content-id cache
 * salt) are transport decoration, not location — two spellings of one file
 * must collapse to a single claim instead of counting as an ambiguity.
 */
private fun locationIdentity(raw: String): String {
    val location = raw.substringBefore(FRAGMENT_SEPARATOR).substringBefore(QUERY_SEPARATOR)
    return if (location.startsWith(FILE_URI_PREFIX)) {
        requireNotNull(parseUriPath(location)) { "Invalid file URI in image locations: $location" }
    } else {
        location
    }
}

private fun isAbsoluteOrRemoteImageUrl(value: String): Boolean {
    val lower = value.lowercase(java.util.Locale.ROOT)
    return lower.startsWith(PATH_ROOT_PREFIX) ||
        lower.startsWith(CONTENT_URI_PREFIX) ||
        lower.startsWith(FILE_URI_PREFIX) ||
        lower.startsWith(HTTP_URI_PREFIX) ||
        lower.startsWith(HTTPS_URI_PREFIX) ||
        lower.startsWith(DATA_IMAGE_PREFIX)
}
