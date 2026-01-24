package com.lomo.app.feature.main

import android.content.Context
import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.ui.component.markdown.MarkdownParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.inject.Inject

class MemoUiMapper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: MemoRepository,
    ) {
        fun mapToUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): MemoUiModel {
            var newContent = memo.content

            // Bug 3 fix: Remove tags from content display (tags are shown separately in footer)
            // Match hashtags at word boundaries: #tag, #tag/subtag, #标签
            newContent =
                newContent.replace(Regex("(^|\\s)#[\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*")) { match ->
                    // Keep the leading whitespace if it was a space (not start of line)
                    if (match.value.startsWith(" ") || match.value.startsWith("\t")) " " else ""
                }
            // Clean up multiple consecutive spaces/newlines left after tag removal
            newContent = newContent.replace(Regex(" {2,}"), " ")
            newContent = newContent.replace(Regex("\\n{3,}"), "\n\n")
            newContent = newContent.trim()

            // Helper to resolve images
            fun resolveImageModel(
                imageUrl: String,
                isWikiStyle: Boolean,
            ): Any {
                val cacheKey =
                    if (imageUrl.startsWith("../")) imageUrl.substringAfterLast("/") else imageUrl
                imageMap[cacheKey]?.let {
                    return it
                }

                if (imageUrl.startsWith("/") ||
                    imageUrl.startsWith("content://") ||
                    imageUrl.startsWith("http")
                ) {
                    return imageUrl
                }

                // Fallback I/O removed for performance.
                // Ensure repository.syncImageCache() runs to populate imageMap.

                val basePath = if (isWikiStyle) (imagePath ?: rootPath) else rootPath
                if (basePath != null) {
                    if (basePath.startsWith("content://") || basePath.startsWith("file://")) {
                        // Check if image is relative
                        if (imageUrl.startsWith("../")) {
                            // Cannot resolve parent of URI easily without context, simply return raw
                            // for now or try weak resolution
                            return imageUrl // Better than returning basePath which is a directory
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

            // 1. Wiki-style
            newContent =
                newContent.replace(Regex("!\\[\\[(.*?)\\]\\]")) { match ->
                    val path = match.groupValues[1]
                    val resolved = resolveImageModel(path, isWikiStyle = true)
                    val finalUrl = (resolved as? File)?.absolutePath ?: resolved.toString()
                    "![]($finalUrl)"
                }

            // 2. Standard - Skip audio files (voice memos) which should NOT be resolved as images
            val audioExtensions = setOf(".m4a", ".mp3", ".aac", ".wav")
            newContent =
                newContent.replace(Regex("!\\[(.*?)\\]\\((.*?)\\)")) { match ->
                    val alt = match.groupValues[1]
                    val path = match.groupValues[2]

                    // Don't process voice memos - they need their relative paths preserved
                    if (audioExtensions.any { path.lowercase().endsWith(it) }) {
                        match.value // Return unchanged
                    } else {
                        val resolved = resolveImageModel(path, isWikiStyle = false)
                        val finalUrl = (resolved as? File)?.absolutePath ?: resolved.toString()
                        "![$alt]($finalUrl)"
                    }
                }

            // Parse Markdown
            // Note: MarkdownParser.parse is computationally expensive, ensure this runs on background
            // thread (it does in ViewModel)
            val parsedNode = MarkdownParser.parse(newContent)

            // Collect images for preloading
            val imageUrls = mutableListOf<String>()
            val imageRegex = Regex("!\\[.*?\\]\\((.*?)\\)")
            imageRegex.findAll(newContent).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    imageUrls.add(url)
                }
            }

            return MemoUiModel(
                memo = memo,
                processedContent = newContent,
                markdownNode = parsedNode,
                tags = memo.tags.toImmutableList(),
                imageUrls = imageUrls.toImmutableList(),
            )
        }
    }
