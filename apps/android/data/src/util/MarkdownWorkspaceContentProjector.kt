package com.lomo.data.util

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.markdown.toMemoContentAnalysis
import com.lomo.domain.repository.MarkdownWorkspaceRepository

/**
 * Data projection boundary over the single active Rust workspace session.
 *
 * Free-floating memo body text (not already projected by a workspace scan) is analyzed by one
 * owner [renderMarkdown] call. Callers that need tags, attachments, and query flags together must
 * call [analyze] once and reuse the result — never chain [extractTags]/[extractInlineAttachments]
 * with a second [analyze] over the same body.
 */
class MarkdownWorkspaceContentProjector(
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
    fun analyze(content: String): MemoContentAnalysis =
        markdownWorkspaceRepository.renderMarkdown(content).toMemoContentAnalysis()

    fun extractTags(content: String): List<String> = analyze(content).tags

    fun extractInlineAttachments(content: String): List<String> =
        analyze(content).let { analysis -> analysis.imageUrls + analysis.audioUrls }

    fun extractLocalAttachmentPaths(content: String): List<String> =
        extractInlineAttachments(content)
            .filter { path ->
                path.isNotEmpty() &&
                    !path.startsWith("http://", ignoreCase = true) &&
                    !path.startsWith("https://", ignoreCase = true)
            }.distinct()

    fun extractAudioLinks(content: String): List<String> =
        extractInlineAttachments(content).filter(MediaFileExtensions::hasAudioExtension)
}
