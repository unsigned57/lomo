package com.lomo.app.util

internal fun String.toStyledShareTextFromHtmlFragment(imagePlaceholder: String): StyledShareText {
    val state = StyledShareTextState(imagePlaceholder)
    var index = 0
    while (index < length) {
        val tagStart = indexOf('<', startIndex = index)
        if (tagStart < 0) {
            state.appendText(substring(index))
            index = length
        } else {
            index = consumeShareHtmlSegment(state, index, tagStart)
        }
    }
    return state.toStyledShareText()
}

private fun String.consumeShareHtmlSegment(
    state: StyledShareTextState,
    index: Int,
    tagStart: Int,
): Int {
    if (tagStart > index) {
        state.appendText(substring(index, tagStart))
    }
    val tagEnd = indexOf('>', startIndex = tagStart + 1)
    if (tagEnd < 0) {
        state.appendText(substring(tagStart))
        return length
    }
    val literal = substring(tagStart, tagEnd + 1)
    if (!state.consumeHtml(literal)) {
        state.appendText(literal)
    }
    return tagEnd + 1
}

internal sealed interface HtmlShareTag {
    data object LineBreak : HtmlShareTag

    data class OpenStyle(
        val kind: ShareInlineStyleKind,
    ) : HtmlShareTag

    data class CloseStyle(
        val kind: ShareInlineStyleKind,
    ) : HtmlShareTag
}

internal fun String.toSupportedHtmlShareTag(): HtmlShareTag? {
    val match = HTML_TAG_PATTERN.matchEntire(trim()) ?: return null
    val isClosing = match.groupValues[1] == "/"
    val tagName = match.groupValues[2].lowercase()
    if (tagName == "br") return HtmlShareTag.LineBreak
    val kind =
        when (tagName) {
            "b", "strong" -> ShareInlineStyleKind.Bold
            "i", "em" -> ShareInlineStyleKind.Italic
            "s", "strike", "del" -> ShareInlineStyleKind.Strikethrough
            "u", "ins" -> ShareInlineStyleKind.Underline
            "code" -> ShareInlineStyleKind.InlineCode
            else -> return null
        }
    return if (isClosing) {
        HtmlShareTag.CloseStyle(kind)
    } else {
        HtmlShareTag.OpenStyle(kind)
    }
}

internal fun MutableList<ShareInlineStyleKind>.removeLastMatching(kind: ShareInlineStyleKind) {
    val index = indexOfLast { it == kind }
    if (index >= 0) {
        removeAt(index)
    }
}

private val HTML_TAG_PATTERN = Regex("""<\s*(/?)\s*([A-Za-z][A-Za-z0-9:-]*)(?:\s+[^>]*)?/?>""")
