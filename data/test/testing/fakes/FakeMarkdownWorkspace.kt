package com.lomo.data.testing.fakes

import com.lomo.data.engine.WorkspaceMarkdownOwner
import com.lomo.data.engine.WorkspaceMemoSummarySnapshot
import com.lomo.data.util.MarkdownWorkspaceContentProjector
import com.lomo.domain.model.StorageTimestampFormats
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
    ): String = error("toggleTask is not expected in this fake")
}

internal fun fakeMarkdownWorkspaceContentProjector(
    repository: MarkdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(),
): MarkdownWorkspaceContentProjector = MarkdownWorkspaceContentProjector(repository)

/**
 * In-memory [WorkspaceMarkdownOwner] for repository tests.
 *
 * Maintains memo summaries keyed by root path (`null` = main, `".trash"` = trash).
 */
internal class FakeWorkspaceMarkdownOwner : WorkspaceMarkdownOwner {
    private val byRoot = linkedMapOf<String?, MutableList<WorkspaceMemoSummarySnapshot>>()

    fun seedMemo(
        rootPath: String? = null,
        summary: WorkspaceMemoSummarySnapshot,
    ) {
        val bucket = byRoot.getOrPut(rootPath) { mutableListOf() }
        bucket.removeAll { it.identity == summary.identity }
        bucket.add(summary)
    }

    fun clear() {
        byRoot.clear()
    }

    override fun scanWorkspace(rootPath: String?): Sequence<WorkspaceMemoSummarySnapshot> =
        byRoot[rootPath].orEmpty().asSequence()

    override fun replaceMemo(
        rootPath: String?,
        filename: String,
        identity: String,
        content: String,
    ): Boolean {
        val items = byRoot[rootPath] ?: return false
        val index =
            items.indexOfFirst { item ->
                item.identity == identity && item.path.substringAfterLast('/') == filename
            }
        if (index < 0) return false
        val previous = items[index]
        items[index] =
            previous.copy(
                content = content,
                tags = extractTestTags(content),
                attachments = extractTestAttachments(content),
            )
        return true
    }

    override fun removeMemo(
        rootPath: String?,
        filename: String,
        identity: String,
    ): Boolean {
        val items = byRoot[rootPath] ?: return false
        val before = items.size
        items.removeAll { item ->
            item.identity == identity && item.path.substringAfterLast('/') == filename
        }
        return items.size < before
    }
}

internal fun testWorkspaceMemoSummary(
    path: String,
    identity: String,
    timePart: String,
    content: String,
    tags: List<String> = extractTestTags(content),
    attachments: List<String> = extractTestAttachments(content),
    hasTodo: Boolean = content.contains("[ ]") || content.contains("[x]", ignoreCase = true),
    hasUrl: Boolean =
        content.contains("http://", ignoreCase = true) ||
            content.contains("https://", ignoreCase = true),
): WorkspaceMemoSummarySnapshot =
    WorkspaceMemoSummarySnapshot(
        path = path,
        identity = identity,
        timePart = timePart,
        fingerprint = "f".repeat(64),
        tags = tags,
        attachments = attachments,
        reminders = emptyList(),
        hasTodo = hasTodo,
        hasUrl = hasUrl,
        content = content,
        bodyStart = 0uL,
        bodyEnd = content.encodeToByteArray().size.toULong(),
        startLine = 1u,
        endLine = 1u,
    )

/**
 * Seeds one summary per Lomo time-header block in a markdown shard for store/projector tests.
 * Line splitting is only for test fixture layout, not production Markdown semantics.
 */
internal fun FakeWorkspaceMarkdownOwner.seedFromMarkdownShard(
    rootPath: String?,
    filename: String,
    markdown: String,
    dateKey: String = filename.removeSuffix(".md"),
) {
    splitTestMemoBlocks(markdown).forEachIndexed { ordinal, block ->
        val identity = "${dateKey}_${block.timePart}_$ordinal"
        seedMemo(
            rootPath = rootPath,
            summary =
                testWorkspaceMemoSummary(
                    path = filename,
                    identity = identity,
                    timePart = block.timePart,
                    content = block.content,
                ),
        )
    }
}

private data class TestMemoBlock(
    val timePart: String,
    val content: String,
)

private fun splitTestMemoBlocks(markdown: String): List<TestMemoBlock> {
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<TestMemoBlock>()
    var index = 0
    while (index < lines.size) {
        val header = StorageTimestampFormats.parseMemoHeaderLine(lines[index])
        if (header == null) {
            index++
            continue
        }
        index++
        val bodyLines = mutableListOf(header.contentPart)
        while (index < lines.size && StorageTimestampFormats.parseMemoHeaderLine(lines[index]) == null) {
            bodyLines.add(lines[index])
            index++
        }
        blocks.add(
            TestMemoBlock(
                timePart = header.timePart,
                content = bodyLines.joinToString("\n").trim(),
            ),
        )
    }
    return blocks
}

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
