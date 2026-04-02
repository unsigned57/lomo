package com.lomo.ui.text

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing

data class RawMemoParagraph(
    val text: String,
)

private val RawMemoParagraphGapRegex = Regex("""(?:\r?\n[ \t]*){2,}""")

fun splitRawMemoParagraphs(rawText: String): List<RawMemoParagraph> {
    if (rawText.isBlank()) return emptyList()

    val normalized = rawText.replace("\r\n", "\n")
    val paragraphs = mutableListOf<RawMemoParagraph>()
    var start = 0

    RawMemoParagraphGapRegex.findAll(normalized).forEach { match ->
        val paragraphText = normalized.substring(start, match.range.first)
        if (paragraphText.isNotBlank()) {
            paragraphs += RawMemoParagraph(paragraphText)
        }
        start = match.range.last + 1
    }

    val tail = normalized.substring(start)
    if (tail.isNotBlank()) {
        paragraphs += RawMemoParagraph(tail)
    }

    return paragraphs
}

fun resolveRawMemoPlainTextStyle(
    typography: Typography,
    text: CharSequence,
): TextStyle = typography.memoBodyTextStyle().scriptAwareFor(text)

fun rawMemoParagraphSpacing() = memoParagraphBlockSpacing()
