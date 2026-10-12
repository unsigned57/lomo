package com.lomo.app.testing.fakes

import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownAttachment
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository

/**
 * App-test Markdown workspace owner that projects plain-text IR without reintroducing a production
 * Kotlin Markdown parser. Sufficient for ViewModel/mapper wiring tests.
 */
class FakeMarkdownWorkspaceRepository(
    private val plainTextTransform: (String) -> String = { content -> content },
    private val tagNames: (String) -> List<String> = { content ->
        Regex("""#([\p{L}\p{N}_/]+)""")
            .findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    },
    private val attachments: (String) -> List<MarkdownAttachment> = { content ->
        val md = Regex("""!\[[^\]]*]\(([^)]+)\)""").findAll(content).map { it.groupValues[1] }
        val wiki = Regex("""!\[\[([^\]|]+)(?:\|[^\]]+)?]]""").findAll(content).map { it.groupValues[1] }
        (md + wiki)
            .distinct()
            .map { destination -> MarkdownAttachment(destination, MediaClass.IMAGE) }
            .toList()
    },
    private val onRender: ((String) -> Unit)? = null,
) : MarkdownWorkspaceRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument {
        onRender?.invoke(content)
        val tags = tagNames(content)
        val plain = plainTextTransform(content)
        return MarkdownRenderDocument(
            sourceByteLength = content.encodeToByteArray().size.toULong(),
            plainText = plain,
            tagNames = tags,
            attachments = attachments(content),
            blocks = emptyList(),
        )
    }

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): com.lomo.domain.model.MemoDocumentMutation = error("toggleTask is not expected in this fake")
}

/**
 * @param dispatcherProvider specs that drive the mapper through virtual time (mutation markers,
 *   paging transforms asserted after runCurrent) pass their own dispatcher so the background-map
 *   lane stays on the test scheduler; pure mapper suites keep the real default lane.
 */
fun testMemoUiMapper(
    workspace: MarkdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(),
    dispatcherProvider: com.lomo.domain.usecase.DispatcherProvider =
        com.lomo.domain.usecase.DefaultDispatcherProvider(),
): com.lomo.app.feature.main.MemoUiMapper =
    com.lomo.app.feature.main.MemoUiMapper(
        dispatcherProvider = dispatcherProvider,
        markdownWorkspaceRepository = workspace,
    )

fun emptyRenderDocument(source: String = ""): MarkdownRenderDocument =
    MarkdownRenderDocument(
        sourceByteLength = source.encodeToByteArray().size.toULong(),
        plainText = source,
        tagNames = emptyList(),
        attachments = emptyList(),
        blocks = emptyList(),
    )
