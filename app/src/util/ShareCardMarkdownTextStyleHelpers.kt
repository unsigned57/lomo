package com.lomo.app.util

import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderTableCell

internal fun MarkdownRenderTableCell.toStyledShareText(): StyledShareText = inlines.toStyledShareText("[Image]")

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

internal fun List<MarkdownRenderInline>.toStyledShareText(imagePlaceholder: String): StyledShareText {
    val state = StyledShareTextState(imagePlaceholder)
    forEach { inline ->
        inline.appendShareText(state)
    }
    return state.toStyledShareText()
}

private fun MarkdownRenderInline.appendShareText(
    state: StyledShareTextState,
) {
    when (this) {
        is MarkdownRenderInline.Text -> state.appendText(text)
        is MarkdownRenderInline.Code -> {
            val start = state.output.length
            state.appendText(text)
            state.styles += ShareInlineStyleRange(start, state.output.length, ShareInlineStyleKind.InlineCode)
        }
        is MarkdownRenderInline.Strong ->
            appendStyledChildren(state, ShareInlineStyleKind.Bold, inlines)
        is MarkdownRenderInline.Emphasis ->
            appendStyledChildren(state, ShareInlineStyleKind.Italic, inlines)
        is MarkdownRenderInline.Strikethrough ->
            appendStyledChildren(state, ShareInlineStyleKind.Strikethrough, inlines)
        is MarkdownRenderInline.Link ->
            appendStyledChildren(state, ShareInlineStyleKind.Link, inlines)
        is MarkdownRenderInline.Highlight ->
            appendStyledChildren(state, ShareInlineStyleKind.Highlight, inlines)
        is MarkdownRenderInline.Image -> state.appendImagePlaceholder()
        is MarkdownRenderInline.SoftBreak,
        is MarkdownRenderInline.HardBreak,
        -> state.appendLineBreak()
        is MarkdownRenderInline.HtmlInline -> {
            if (!state.consumeHtml(literal)) {
                state.appendText(literal)
            }
        }
        is MarkdownRenderInline.Tag -> state.appendText("#$name")
        is MarkdownRenderInline.Reminder -> state.appendText(token)
        is MarkdownRenderInline.WikiReference -> inlines.forEach { it.appendShareText(state) }
    }
}

private fun appendStyledChildren(
    state: StyledShareTextState,
    kind: ShareInlineStyleKind,
    inlines: List<MarkdownRenderInline>,
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
