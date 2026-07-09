package com.lomo.ui.component.card

import com.lomo.ui.component.markdown.MarkdownKnownTagFilter
import com.lomo.ui.component.markdown.stripReminderTokens

private const val EXPAND_CHAR_THRESHOLD = 600
private const val EXPAND_LINE_THRESHOLD = 15
internal const val COLLAPSED_SUMMARY_MAX_LINES = 8
private const val COLLAPSED_SUMMARY_MAX_CHARS = 420

fun shouldShowMemoCardExpand(content: String): Boolean =
    content.length > EXPAND_CHAR_THRESHOLD ||
        content.lineSequence().count() > EXPAND_LINE_THRESHOLD

fun buildMemoCardCollapsedSummary(
    content: String,
    tags: Iterable<String> = emptyList(),
): String {
    if (content.isBlank()) return ""

    val lines = mutableListOf<String>()
    var charCount = 0
    val lineIterator = content.lineSequence().iterator()

    while (
        lineIterator.hasNext() &&
        lines.size < COLLAPSED_SUMMARY_MAX_LINES &&
        charCount < COLLAPSED_SUMMARY_MAX_CHARS
    ) {
        val line = sanitizeCollapsedSummaryLine(lineIterator.next(), tags)
        val remaining = COLLAPSED_SUMMARY_MAX_CHARS - charCount
        val clipped = if (line.length > remaining) line.take(remaining).trimEnd() else line

        if (clipped.isNotBlank()) {
            lines.add(clipped)
            charCount += clipped.length
        }
    }

    return lines.joinToString(separator = "\n")
}

private fun sanitizeCollapsedSummaryLine(
    rawLine: String,
    tags: Iterable<String>,
): String {
    val cleanText = MarkdownKnownTagFilter
        .stripInlineTags(
            input =
                rawLine
                    .replace(MARKDOWN_IMAGE_PATTERN, "")
                    .replace(MARKDOWN_LINK_PATTERN, "$1")
                    .replace(MARKDOWN_INLINE_CODE_PATTERN, "$1")
                    .replace(MARKDOWN_BLOCK_PREFIX_PATTERN, "")
                    .replace(MARKDOWN_TASK_PREFIX_PATTERN, ""),
            tags = tags,
        )
    return stripReminderTokens(cleanText).trim()
}

private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[[^\]]*]\([^)]+\)""")
private val MARKDOWN_LINK_PATTERN = Regex("""\[([^\]]+)]\([^)]+\)""")
private val MARKDOWN_INLINE_CODE_PATTERN = Regex("""`([^`]+)`""")
private val MARKDOWN_BLOCK_PREFIX_PATTERN = Regex("""^\s{0,3}(?:#{1,6}\s+|>\s+|[-*+]\s+|\d+\.\s+)""")
private val MARKDOWN_TASK_PREFIX_PATTERN = Regex("""^\s*\[[ xX]\]\s+""")
