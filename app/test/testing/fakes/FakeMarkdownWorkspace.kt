package com.lomo.app.testing.fakes

import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownReminderRepository
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
    private val attachmentDestinations: (String) -> List<String> = { content ->
        val md = Regex("""!\[[^\]]*]\(([^)]+)\)""").findAll(content).map { it.groupValues[1] }
        val wiki = Regex("""!\[\[([^\]|]+)(?:\|[^\]]+)?]]""").findAll(content).map { it.groupValues[1] }
        (md + wiki).distinct().toList()
    },
) : MarkdownWorkspaceRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument {
        val tags = tagNames(content)
        val plain = plainTextTransform(content)
        return MarkdownRenderDocument(
            sourceByteLength = content.encodeToByteArray().size.toULong(),
            plainText = plain,
            tagNames = tags,
            attachmentDestinations = attachmentDestinations(content),
            blocks = emptyList(),
        )
    }

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = "toggled:$memoIdentity:${actionSpan.startByte}-${actionSpan.endByte}"
}

class FakeMarkdownReminderRepository(
    private val remindersByMemo: Map<String, List<ReminderMarker>> = emptyMap(),
) : MarkdownReminderRepository {
    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> =
        remindersByMemo[memoIdentity].orEmpty()

    override suspend fun rewriteReminder(
        reference: com.lomo.domain.model.ReminderReference,
        replacement: String,
    ): String = replacement
}

fun testMemoUiMapper(
    workspace: MarkdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(),
    reminders: MarkdownReminderRepository = FakeMarkdownReminderRepository(),
): com.lomo.app.feature.main.MemoUiMapper =
    com.lomo.app.feature.main.MemoUiMapper(
        markdownWorkspaceRepository = workspace,
        markdownReminderRepository = reminders,
    )

fun emptyRenderDocument(source: String = ""): MarkdownRenderDocument =
    MarkdownRenderDocument(
        sourceByteLength = source.encodeToByteArray().size.toULong(),
        plainText = source,
        tagNames = emptyList(),
        attachmentDestinations = emptyList(),
        blocks = emptyList(),
    )
