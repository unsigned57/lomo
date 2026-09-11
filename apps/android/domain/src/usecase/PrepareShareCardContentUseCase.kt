package com.lomo.domain.usecase

import com.lomo.domain.model.ShareCardContent
import com.lomo.domain.model.ShareCardTextInput
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.MarkdownWorkspaceRepository

/**
 * Domain use case: extract semantic share-card content from the Rust workspace owner IR.
 *
 * Tags come from source tags when present; otherwise from the owner render document. Body text is
 * the owner `plainText` with tag names removed as presentation tokens (not a second Markdown parse).
 *
 * Prefer [invoke] with an already-rendered [MarkdownRenderDocument] when the caller owns one parse
 * for both IR body lines and share tags — free-content entry still renders once via the repository.
 */
class PrepareShareCardContentUseCase(
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
    operator fun invoke(input: ShareCardTextInput): ShareCardContent {
        val document = markdownWorkspaceRepository.renderMarkdown(input.content)
        return invoke(document = document, sourceTags = input.sourceTags)
    }

    operator fun invoke(
        document: MarkdownRenderDocument,
        sourceTags: List<String>,
    ): ShareCardContent {
        val tags = buildShareTags(sourceTags, document.tagNames)
        val bodyText = removeTagTokens(document.plainText, tags).trim()
        return ShareCardContent(
            tags = tags,
            bodyText = bodyText,
        )
    }

    private fun buildShareTags(
        sourceTags: List<String>,
        renderTags: List<String>,
    ): List<String> {
        val normalized =
            sourceTags
                .asSequence()
                .map { it.trim().trimStart('#') }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        if (normalized.isNotEmpty()) return normalized
        return renderTags
            .asSequence()
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun removeTagTokens(
        plainText: String,
        tags: List<String>,
    ): String {
        var stripped = plainText
        tags
            .asSequence()
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .forEach { tag ->
                val escaped = Regex.escape(tag)
                stripped =
                    stripped.replace(Regex("""(^|\s)#$escaped(?=\s|$)""")) { match ->
                        if (match.value.first().isWhitespace()) " " else ""
                    }
            }
        return stripped
    }
}
