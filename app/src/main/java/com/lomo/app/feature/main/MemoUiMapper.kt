package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.ui.component.markdown.ImmutableNode
import com.lomo.ui.component.markdown.MarkdownParser
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class MemoUiMapper
    @Inject
    constructor() {
        suspend fun mapToUiModels(
            memos: List<Memo>,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            deletingIds: Set<String> = emptySet(),
        ): List<MemoUiModel> =
            withContext(Dispatchers.Default) {
                memos.map { memo ->
                    mapToUiModel(
                        memo = memo,
                        rootPath = rootPath,
                        imagePath = imagePath,
                        imageMap = imageMap,
                        isDeleting = memo.id in deletingIds,
                    )
                }
            }

        fun mapToUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            isDeleting: Boolean = false,
        ): MemoUiModel {
            val processedContent = buildProcessedContent(memo.content, rootPath, imagePath, imageMap)
            val parsedNode = parseMarkdownCached(processedContent)
            val imageUrls = extractImageUrls(processedContent)

            return MemoUiModel(
                memo = memo,
                processedContent = processedContent,
                markdownNode = parsedNode,
                tags = memo.tags.toImmutableList(),
                imageUrls = imageUrls,
                isDeleting = isDeleting,
            )
        }

        private fun buildProcessedContent(
            content: String,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): String {
            var resolvedContent = content

            resolvedContent =
                WIKI_IMAGE_REGEX.replace(resolvedContent) { match ->
                    val path = match.groupValues[1]
                    val resolved =
                        resolveImageModel(path, isWikiStyle = true, rootPath = rootPath, imagePath = imagePath, imageMap = imageMap)
                    val finalUrl = (resolved as? File)?.absolutePath ?: resolved.toString()
                    "![]($finalUrl)"
                }

            resolvedContent =
                MARKDOWN_IMAGE_REGEX.replace(resolvedContent) { match ->
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

            return resolvedContent
        }

        private fun resolveImageModel(
            imageUrl: String,
            isWikiStyle: Boolean,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): Any {
            val cacheKey = if (imageUrl.startsWith("../")) imageUrl.substringAfterLast("/") else imageUrl
            imageMap[cacheKey]?.let { return it }

            if (imageUrl.startsWith("/") || imageUrl.startsWith("content://") || imageUrl.startsWith("http")) {
                return imageUrl
            }

            val basePath = if (isWikiStyle) (imagePath ?: rootPath) else rootPath
            if (basePath != null) {
                if (basePath.startsWith("content://") || basePath.startsWith("file://")) {
                    if (imageUrl.startsWith("../")) {
                        return imageUrl
                    }
                    return Uri
                        .parse(basePath)
                        .buildUpon()
                        .appendPath(imageUrl)
                        .build()
                        .toString()
                }

                return if (imageUrl.startsWith("../")) {
                    val parentDir = File(basePath).parent ?: basePath
                    File(parentDir, imageUrl.removePrefix("../"))
                } else {
                    File(basePath, imageUrl)
                }
            }
            return imageUrl
        }

        private fun parseMarkdownCached(content: String): ImmutableNode {
            synchronized(cacheLock) {
                markdownCache[content]?.let { return it }
            }

            val parsed = MarkdownParser.parse(content)
            synchronized(cacheLock) {
                markdownCache[content] = parsed
            }
            return parsed
        }

        private fun extractImageUrls(content: String): ImmutableList<String> {
            val imageUrls = mutableListOf<String>()
            EXTRACT_IMAGE_URL_REGEX.findAll(content).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    imageUrls.add(url)
                }
            }
            return imageUrls.toImmutableList()
        }

        private companion object {
            private const val MARKDOWN_CACHE_SIZE = 512
            private val WIKI_IMAGE_REGEX = Regex("!\\[\\[(.*?)\\]\\]")
            private val MARKDOWN_IMAGE_REGEX = Regex("!\\[(.*?)\\]\\((.*?)\\)")
            private val EXTRACT_IMAGE_URL_REGEX = Regex("!\\[.*?\\]\\((.*?)\\)")
            private val AUDIO_EXTENSIONS = setOf(".m4a", ".mp3", ".aac", ".wav")
        }

        private val cacheLock = Any()

        private val markdownCache =
            object : LinkedHashMap<String, ImmutableNode>(MARKDOWN_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImmutableNode>): Boolean = size > MARKDOWN_CACHE_SIZE
            }
    }
