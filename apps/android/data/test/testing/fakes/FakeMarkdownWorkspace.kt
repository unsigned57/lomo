package com.lomo.data.testing.fakes

import com.lomo.data.util.MarkdownWorkspaceContentProjector
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository

/**
 * Test-only Markdown workspace repository that projects tags/attachments onto the domain IR surface
 * without reintroducing a production Kotlin MarkdownParser authority.
 *
 * Minimal typed blocks are synthesized so [toMemoContentAnalysis] can project hasTodo/hasUrl from
 * the same document facts as tags/attachments (one fake owner pass).
 */
internal class FakeMarkdownWorkspaceRepository(
    private val tagExtractor: (String) -> List<String> = ::extractTestTags,
    private val attachmentExtractor: (String) -> List<String> = ::extractTestAttachments,
) : MarkdownWorkspaceRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument {
        val bytes = content.encodeToByteArray().size.toULong()
        val span = MarkdownSourceSpan(0uL, bytes)
        val blocks = mutableListOf<MarkdownRenderBlock>()
        val hasTodo = content.contains("[ ]") || content.contains("[x]", ignoreCase = true)
        val hasUrl =
            content.contains("http://", ignoreCase = true) ||
                content.contains("https://", ignoreCase = true) ||
                content.contains("mailto:", ignoreCase = true)
        if (hasTodo) {
            blocks +=
                MarkdownRenderBlock.ListBlock(
                    sourceSpan = span,
                    ordered = false,
                    startNumber = 1uL,
                    items =
                        listOf(
                            MarkdownRenderListItem(
                                sourceSpan = span,
                                actionSpan = span,
                                checked = content.contains("[x]", ignoreCase = true),
                                blocks =
                                    listOf(
                                        MarkdownRenderBlock.Paragraph(
                                            sourceSpan = span,
                                            inlines =
                                                listOf(
                                                    MarkdownRenderInline.Text(span, "task"),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                )
        }
        if (hasUrl) {
            val url =
                Regex("""https?://\S+|mailto:\S+""", RegexOption.IGNORE_CASE)
                    .find(content)
                    ?.value
                    ?: "https://example.com"
            blocks +=
                MarkdownRenderBlock.Paragraph(
                    sourceSpan = span,
                    inlines =
                        listOf(
                            MarkdownRenderInline.Link(
                                sourceSpan = span,
                                destination = url,
                                title = null,
                                inlines = listOf(MarkdownRenderInline.Text(span, url)),
                            ),
                        ),
                )
        }
        if (blocks.isEmpty()) {
            blocks +=
                MarkdownRenderBlock.Paragraph(
                    sourceSpan = span,
                    inlines = listOf(MarkdownRenderInline.Text(span, content.take(32))),
                )
        }
        return MarkdownRenderDocument(
            sourceByteLength = bytes,
            plainText = content,
            tagNames = tagExtractor(content),
            attachmentDestinations = attachmentExtractor(content),
            blocks = blocks,
        )
    }

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): com.lomo.domain.model.MemoDocumentMutation = error("toggleTask is not expected in this fake")
}

internal fun fakeMarkdownWorkspaceContentProjector(
    repository: MarkdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(),
): MarkdownWorkspaceContentProjector = MarkdownWorkspaceContentProjector(repository)

private val TEST_TAG_PATTERN =
    Regex("""(?:^|[\s])#([\p{L}\p{N}\p{So}\p{Sc}_][\p{L}\p{N}\p{So}\p{Sc}_/]*)""")
private val TEST_MD_IMAGE_PATTERN = Regex("""!\[[^\]]*]\(([^)]+)\)""")
private val TEST_WIKI_IMAGE_PATTERN = Regex("""!\[\[([^\]|]+)(?:\|[^\]]+)?]]""")
private val TEST_AUDIO_LINK_PATTERN =
    Regex("""\[[^\]]*]\(([^)]+\.(?:mp3|m4a|ogg|wav|aac|flac))\)""", RegexOption.IGNORE_CASE)

internal fun extractTestTags(content: String): List<String> =
    TEST_TAG_PATTERN
        .findAll(content)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

internal fun extractTestAttachments(content: String): List<String> {
    val images =
        TEST_MD_IMAGE_PATTERN.findAll(content).map { it.groupValues[1] } +
            TEST_WIKI_IMAGE_PATTERN.findAll(content).map { it.groupValues[1] }
    val audio = TEST_AUDIO_LINK_PATTERN.findAll(content).map { it.groupValues[1] }
    return (images + audio).distinct().toList()
}
