package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.ui.component.markdown.MarkdownRenderState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class MemoEditorReminderChip(val label: String, val range: TextRange)

internal data class MemoEditorContentState(
    val sourceText: String,
    val renderState: MarkdownRenderState,
    val reminders: ImmutableList<MemoEditorReminderChip> = persistentListOf(),
)

/** Only owner-issued reminder nodes become metadata; code and ordinary dates remain editable text. */
internal fun projectMemoEditorReminders(
    sourceText: String,
    document: MarkdownRenderDocument,
    dueAt: (String) -> LocalDateTime,
    dateFormat: String,
    timeFormat: String,
): ImmutableList<MemoEditorReminderChip> {
    val bytes = sourceText.encodeToByteArray()
    require(document.sourceByteLength == bytes.size.toULong()) { "Reminder render document must describe this source" }
    val formatter = DateTimeFormatter.ofPattern("$dateFormat $timeFormat")
    val nodes = buildList { document.blocks.forEach { collectReminderNodes(it, this) } }
        .sortedBy { it.sourceSpan.startByte }
    var previousByte = 0
    var previousCharacter = 0
    return nodes.map { node ->
        val start = node.sourceSpan.startByte.toInt()
        val end = node.sourceSpan.endByte.toInt()
        require(start >= previousByte && end in start..bytes.size) { "Reminder spans must be disjoint source ranges" }
        val characterStart = previousCharacter + bytes.decodeToString(previousByte, start, true).length
        val token = bytes.decodeToString(start, end, true)
        require(token == node.token) { "Reminder span does not match its owner token" }
        val characterEnd = characterStart + token.length
        previousByte = end
        previousCharacter = characterEnd
        MemoEditorReminderChip(
            label = formatter.format(dueAt(token)),
            range = reminderEditorRemovalRange(sourceText, characterStart, characterEnd),
        )
    }.toImmutableList()
}

private fun reminderEditorRemovalRange(text: String, start: Int, end: Int): TextRange {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val nextBreak = text.indexOf('\n', end)
    val lineEnd = if (nextBreak < 0) text.length else nextBreak
    if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
        return if (nextBreak >= 0) {
            TextRange(lineStart, nextBreak + 1)
        } else {
            TextRange(if (lineStart > 0) lineStart - 1 else 0, lineEnd)
        }
    }
    return when {
        start > lineStart && text[start - 1] == ' ' -> TextRange(start - 1, end)
        start == lineStart && end < lineEnd && text[end] == ' ' -> TextRange(start, end + 1)
        else -> TextRange(start, end)
    }
}

private fun collectReminderNodes(block: MarkdownRenderBlock, result: MutableList<MarkdownRenderInline.Reminder>) {
    when (block) {
        is MarkdownRenderBlock.Paragraph -> block.inlines.forEach { collectReminderNodes(it, result) }

        is MarkdownRenderBlock.Heading -> block.inlines.forEach { collectReminderNodes(it, result) }

        is MarkdownRenderBlock.BlockQuote -> block.blocks.forEach { collectReminderNodes(it, result) }

        is MarkdownRenderBlock.ListBlock -> block.items.forEach { item ->
            item.blocks.forEach { collectReminderNodes(it, result) }
        }

        is MarkdownRenderBlock.Table -> (block.header + block.rows.flatten()).forEach { cell ->
            cell.inlines.forEach { collectReminderNodes(it, result) }
        }

        else -> Unit
    }
}

private fun collectReminderNodes(inline: MarkdownRenderInline, result: MutableList<MarkdownRenderInline.Reminder>) {
    when (inline) {
        is MarkdownRenderInline.Reminder -> result += inline
        is MarkdownRenderInline.Strong -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.Emphasis -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.Strikethrough -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.Underline -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.Highlight -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.Link -> inline.inlines.forEach { collectReminderNodes(it, result) }
        is MarkdownRenderInline.WikiReference -> inline.inlines.forEach { collectReminderNodes(it, result) }
        else -> Unit
    }
}
