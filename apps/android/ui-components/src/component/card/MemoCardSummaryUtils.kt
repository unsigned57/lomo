package com.lomo.ui.component.card

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.ui.component.markdown.MarkdownIrPresentationItem
import com.lomo.ui.component.markdown.MarkdownIrPresentationPlan
import com.lomo.ui.component.markdown.MarkdownPresentationPolicy
import com.lomo.ui.component.markdown.buildMarkdownIrPresentationPlan

internal const val COLLAPSED_SUMMARY_MAX_LINES = 8
private const val COLLAPSED_SUMMARY_MAX_CHARS = 420

/** Hidden upstream content requires expansion even when the received preview is short. */
internal fun shouldShowMemoCardExpand(
    contentHeightPx: Int,
    collapsedHeightPx: Int,
    hasHiddenContent: Boolean,
    hasImages: Boolean,
): Boolean {
    require(contentHeightPx >= 0 && collapsedHeightPx > 0)
    return hasHiddenContent || (!hasImages && contentHeightPx > collapsedHeightPx)
}

/** Builds the collapsed preview only from Rust-issued render facts. */
fun buildMemoCardCollapsedSummary(
    document: MarkdownRenderDocument,
    policy: MarkdownPresentationPolicy = MarkdownPresentationPolicy.MEMO_CARD,
): String = buildMemoCardCollapsedSummary(
    plan = buildMarkdownIrPresentationPlan(document = document, policy = policy),
)

/** Builds the collapsed preview from a precomputed presentation plan. */
fun buildMemoCardCollapsedSummary(plan: MarkdownIrPresentationPlan): String {
    val lines = mutableListOf<String>()
    var charCount = 0

    plan.items
        .asSequence()
        .filterIsInstance<MarkdownIrPresentationItem.Block>()
        .flatMap { item -> item.block.summaryLines().asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .takeWhile { lines.size < COLLAPSED_SUMMARY_MAX_LINES && charCount < COLLAPSED_SUMMARY_MAX_CHARS }
        .forEach { line ->
            val remaining = COLLAPSED_SUMMARY_MAX_CHARS - charCount
            val clipped = if (line.length > remaining) line.take(remaining).trimEnd() else line
            if (clipped.isNotBlank()) {
                lines += clipped
                charCount += clipped.length
            }
        }

    return lines.joinToString(separator = "\n")
}

private fun MarkdownRenderBlock.summaryLines(): List<String> = when (this) {
    is MarkdownRenderBlock.Paragraph -> listOf(inlines.summaryText())

    is MarkdownRenderBlock.Heading -> listOf(inlines.summaryText())

    is MarkdownRenderBlock.BlockQuote -> blocks.flatMap(MarkdownRenderBlock::summaryLines)

    is MarkdownRenderBlock.ListBlock ->
        items.flatMap { item -> item.blocks.flatMap(MarkdownRenderBlock::summaryLines) }

    is MarkdownRenderBlock.CodeBlock -> literal.lineSequence().toList()

    is MarkdownRenderBlock.ThematicBreak -> emptyList()

    is MarkdownRenderBlock.Table ->
        (listOf(header) + rows).map { row -> row.joinToString(" | ") { cell -> cell.inlines.summaryText() } }

    is MarkdownRenderBlock.HtmlBlock -> listOf(literal)
}

private fun List<MarkdownRenderInline>.summaryText(): String = buildString {
    this@summaryText.forEach { inline ->
        when (inline) {
            is MarkdownRenderInline.Text -> append(inline.text)

            is MarkdownRenderInline.Strong -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Emphasis -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Strikethrough -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Underline -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Highlight -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Code -> append(inline.text)

            is MarkdownRenderInline.Link -> append(inline.inlines.summaryText())

            is MarkdownRenderInline.Image,
            is MarkdownRenderInline.Tag,
            is MarkdownRenderInline.Reminder,
            -> Unit

            is MarkdownRenderInline.WikiReference -> append(inline.inlines.summaryText().ifBlank { inline.target })

            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            -> append('\n')

            is MarkdownRenderInline.HtmlInline -> append(inline.literal)
        }
    }
}

/**
 * Whether the card body must be clamped to the collapsed height budget.
 *
 * The clamp is a geometry fact, not an affordance fact: a card that is not user-expanded and
 * carries no media never presents more than the collapsed budget. Coupling it to the measured
 * overflow instead let an unmeasured body render at full height for its first frame and then
 * animate shut, so every fresh composition (page return, fast scroll) visibly expanded and
 * collapsed the card by itself.
 */
internal fun shouldClampMemoCardBody(requestedExpanded: Boolean, hasImages: Boolean): Boolean =
    !hasImages && !requestedExpanded
