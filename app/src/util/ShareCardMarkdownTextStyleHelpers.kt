package com.lomo.app.util

import com.lomo.ui.component.markdown.MarkdownSemanticInline
import com.lomo.ui.component.markdown.MarkdownSemanticTableCell

internal fun MarkdownSemanticTableCell.toStyledShareText(): StyledShareText = inlines.toStyledShareText("[Image]")

internal data class StyledShareText(
    val text: String,
    val styles: List<ShareInlineStyleRange>,
)

internal class StyledShareTextState(
    private val imagePlaceholder: String,
) {
    val output = StringBuilder()
    val styles = mutableListOf<ShareInlineStyleRange>()
    private val activeHtmlStyles = mutableListOf<ShareInlineStyleKind>()
    private var trimLeadingWhitespaceAfterBreak = false

    fun appendText(text: String) {
        val normalized =
            if (trimLeadingWhitespaceAfterBreak) {
                text.trimStart().also {
                    trimLeadingWhitespaceAfterBreak = false
                }
            } else {
                text
            }
        if (normalized.isEmpty()) return
        val start = output.length
        output.append(normalized)
        if (start < output.length) {
            activeHtmlStyles.forEach { kind ->
                styles += ShareInlineStyleRange(start, output.length, kind)
            }
        }
    }

    fun appendImagePlaceholder() {
        appendText(imagePlaceholder)
    }

    fun appendLineBreak() {
        trimTrailingWhitespace()
        output.append('\n')
        trimLeadingWhitespaceAfterBreak = true
    }

    fun consumeHtml(literal: String): Boolean {
        val tag = literal.toSupportedHtmlShareTag() ?: return false
        when (tag) {
            HtmlShareTag.LineBreak -> appendLineBreak()
            is HtmlShareTag.OpenStyle -> activeHtmlStyles += tag.kind
            is HtmlShareTag.CloseStyle -> activeHtmlStyles.removeLastMatching(tag.kind)
        }
        return true
    }

    fun toStyledShareText(): StyledShareText =
        StyledShareText(output.toString(), styles)

    private fun trimTrailingWhitespace() {
        var trimmedLength = output.length
        while (trimmedLength > 0 && output[trimmedLength - 1].isWhitespace() && output[trimmedLength - 1] != '\n') {
            trimmedLength--
        }
        if (trimmedLength == output.length) return
        output.setLength(trimmedLength)
        val trimmedStyles =
            styles.mapNotNull { range ->
                val clampedEnd = minOf(range.end, trimmedLength)
                range.takeIf { range.start < clampedEnd }?.copy(end = clampedEnd)
            }
        styles.clear()
        styles += trimmedStyles
    }
}

internal fun List<MarkdownSemanticInline>.toStyledShareText(imagePlaceholder: String): StyledShareText {
    val state = StyledShareTextState(imagePlaceholder)
    forEach { inline ->
        inline.appendShareText(state)
    }
    return state.toStyledShareText()
}

private fun MarkdownSemanticInline.appendShareText(
    state: StyledShareTextState,
) {
    when (this) {
        is MarkdownSemanticInline.Text -> state.appendText(plainText)
        is MarkdownSemanticInline.Code -> {
            val start = state.output.length
            state.appendText(plainText)
            state.styles += ShareInlineStyleRange(start, state.output.length, ShareInlineStyleKind.InlineCode)
        }
        is MarkdownSemanticInline.Strong ->
            appendStyledChildren(state, ShareInlineStyleKind.Bold, inlines)
        is MarkdownSemanticInline.Emphasis ->
            appendStyledChildren(state, ShareInlineStyleKind.Italic, inlines)
        is MarkdownSemanticInline.Strikethrough ->
            appendStyledChildren(state, ShareInlineStyleKind.Strikethrough, inlines)
        is MarkdownSemanticInline.Link ->
            appendStyledChildren(state, ShareInlineStyleKind.Link, inlines)
        is MarkdownSemanticInline.Highlight ->
            appendStyledChildren(state, ShareInlineStyleKind.Highlight, inlines)
        is MarkdownSemanticInline.Image -> state.appendImagePlaceholder()
        MarkdownSemanticInline.SoftBreak,
        MarkdownSemanticInline.HardBreak,
        -> state.appendLineBreak()
        is MarkdownSemanticInline.HtmlInline -> {
            if (!state.consumeHtml(plainText)) {
                state.appendText(plainText)
            }
        }
    }
}

private fun appendStyledChildren(
    state: StyledShareTextState,
    kind: ShareInlineStyleKind,
    inlines: List<MarkdownSemanticInline>,
) {
    val start = state.output.length
    inlines.forEach { inline ->
        inline.appendShareText(state)
    }
    if (start < state.output.length) {
        state.styles += ShareInlineStyleRange(start, state.output.length, kind)
    }
}

internal fun ShareBodyLine.withQuoteMarker(): ShareBodyLine =
    if (type == ShareBodyLineType.Image || text.startsWith(QUOTE_PREFIX)) {
        this
    } else {
        copy(
            text = "$QUOTE_PREFIX$text",
            inlineStyles = inlineStyles.shift(QUOTE_PREFIX.length),
        )
    }

internal fun List<ShareInlineStyleRange>.shift(offset: Int): List<ShareInlineStyleRange> =
    if (offset == 0 || isEmpty()) {
        this
    } else {
        map { range ->
            range.copy(
                start = range.start + offset,
                end = range.end + offset,
            )
        }
    }
