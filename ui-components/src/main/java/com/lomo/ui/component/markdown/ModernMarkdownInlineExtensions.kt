package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration

private const val MODERN_MARKDOWN_HIGHLIGHT_OPEN_MARKER = "LOMO_MD_HL_OPEN"
private const val MODERN_MARKDOWN_HIGHLIGHT_CLOSE_MARKER = "LOMO_MD_HL_CLOSE"
private const val MODERN_MARKDOWN_UNDERLINE_OPEN_MARKER = "LOMO_MD_U_OPEN"
private const val MODERN_MARKDOWN_UNDERLINE_CLOSE_MARKER = "LOMO_MD_U_CLOSE"

private data class PendingInlineMarker(
    val start: Int,
    val placeholder: String,
    val literal: String,
)

internal fun preprocessModernMarkdownInlineExtensions(
    fragment: String,
): String {
    if (!fragment.contains("==") && !fragment.contains("<u>")) {
        return fragment
    }

    val output = StringBuilder(fragment.length)
    val pendingHighlights = ArrayDeque<PendingInlineMarker>()
    val pendingUnderlines = ArrayDeque<PendingInlineMarker>()
    var inlineCodeDelimiterLength: Int? = null
    var index = 0

    while (index < fragment.length) {
        val backtickRunLength = fragment.countRepeatedBackticks(index)
        if (backtickRunLength > 0) {
            output.append(fragment, index, index + backtickRunLength)
            inlineCodeDelimiterLength =
                when {
                    inlineCodeDelimiterLength == null -> backtickRunLength
                    inlineCodeDelimiterLength == backtickRunLength -> null
                    else -> inlineCodeDelimiterLength
                }
            index += backtickRunLength
            continue
        }

        if (inlineCodeDelimiterLength != null) {
            output.append(fragment[index])
            index++
            continue
        }

        when {
            fragment.startsWith("<u>", index) -> {
                val markerStart = output.length
                output.append(MODERN_MARKDOWN_UNDERLINE_OPEN_MARKER)
                pendingUnderlines.addLast(
                    PendingInlineMarker(
                        start = markerStart,
                        placeholder = MODERN_MARKDOWN_UNDERLINE_OPEN_MARKER,
                        literal = "<u>",
                    ),
                )
                index += UNDERLINE_OPEN_TAG_LENGTH
            }

            fragment.startsWith("</u>", index) -> {
                if (pendingUnderlines.isNotEmpty()) {
                    pendingUnderlines.removeLast()
                    output.append(MODERN_MARKDOWN_UNDERLINE_CLOSE_MARKER)
                } else {
                    output.append("</u>")
                }
                index += UNDERLINE_CLOSE_TAG_LENGTH
            }

            fragment.startsWith("==", index) -> {
                if (pendingHighlights.isNotEmpty()) {
                    pendingHighlights.removeLast()
                    output.append(MODERN_MARKDOWN_HIGHLIGHT_CLOSE_MARKER)
                } else {
                    val markerStart = output.length
                    output.append(MODERN_MARKDOWN_HIGHLIGHT_OPEN_MARKER)
                    pendingHighlights.addLast(
                        PendingInlineMarker(
                            start = markerStart,
                            placeholder = MODERN_MARKDOWN_HIGHLIGHT_OPEN_MARKER,
                            literal = "==",
                        ),
                    )
                }
                index += HIGHLIGHT_MARKER_LENGTH
            }

            else -> {
                output.append(fragment[index])
                index++
            }
        }
    }

    restoreUnmatchedInlineMarkers(output, pendingUnderlines)
    restoreUnmatchedInlineMarkers(output, pendingHighlights)
    return output.toString()
}

@OptIn(ExperimentalTextApi::class)
internal fun applyModernMarkdownInlineExtensions(
    annotatedText: AnnotatedString,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString {
    val sourceText = annotatedText.text
    if (!sourceText.contains("LOMO_MD_")) {
        return annotatedText
    }

    val output = StringBuilder(sourceText.length)
    val indexMap = IntArray(sourceText.length + 1)
    val highlightStarts = ArrayDeque<Int>()
    val underlineStarts = ArrayDeque<Int>()
    val extensionRanges = mutableListOf<AnnotatedString.Range<out AnnotatedString.Annotation>>()
    var index = 0

    while (index < sourceText.length) {
        when {
            sourceText.startsWith(MODERN_MARKDOWN_HIGHLIGHT_OPEN_MARKER, index) -> {
                highlightStarts.addLast(output.length)
                index = consumeRemovedMarker(sourceText, index, MODERN_MARKDOWN_HIGHLIGHT_OPEN_MARKER, indexMap, output.length)
            }

            sourceText.startsWith(MODERN_MARKDOWN_HIGHLIGHT_CLOSE_MARKER, index) -> {
                highlightStarts.removeLastOrNull()?.let { start ->
                    if (start < output.length) {
                        extensionRanges +=
                            AnnotatedString.Range(
                                item = tokenSpec.highlightSpanStyle,
                                start = start,
                                end = output.length,
                            )
                    }
                }
                index = consumeRemovedMarker(sourceText, index, MODERN_MARKDOWN_HIGHLIGHT_CLOSE_MARKER, indexMap, output.length)
            }

            sourceText.startsWith(MODERN_MARKDOWN_UNDERLINE_OPEN_MARKER, index) -> {
                underlineStarts.addLast(output.length)
                index = consumeRemovedMarker(sourceText, index, MODERN_MARKDOWN_UNDERLINE_OPEN_MARKER, indexMap, output.length)
            }

            sourceText.startsWith(MODERN_MARKDOWN_UNDERLINE_CLOSE_MARKER, index) -> {
                underlineStarts.removeLastOrNull()?.let { start ->
                    if (start < output.length) {
                        extensionRanges +=
                            AnnotatedString.Range(
                                item = SpanStyle(textDecoration = TextDecoration.Underline),
                                start = start,
                                end = output.length,
                            )
                    }
                }
                index = consumeRemovedMarker(sourceText, index, MODERN_MARKDOWN_UNDERLINE_CLOSE_MARKER, indexMap, output.length)
            }

            else -> {
                output.append(sourceText[index])
                index++
                indexMap[index] = output.length
            }
        }
    }

    val remappedText = output.toString()
    val builder = AnnotatedString.Builder(remappedText)

    annotatedText.spanStyles.forEach { range ->
        remapRange(indexMap, range.start, range.end)?.let { (start, end) ->
            builder.addStyle(range.item, start, end)
        }
    }
    annotatedText.paragraphStyles.forEach { range ->
        remapRange(indexMap, range.start, range.end)?.let { (start, end) ->
            builder.addStyle(range.item, start, end)
        }
    }
    annotatedText.getStringAnnotations(0, annotatedText.length).forEach { range ->
        remapRange(indexMap, range.start, range.end)?.let { (start, end) ->
            builder.addStringAnnotation(range.tag, range.item, start, end)
        }
    }
    annotatedText.getTtsAnnotations(0, annotatedText.length).forEach { range ->
        remapRange(indexMap, range.start, range.end)?.let { (start, end) ->
            builder.addTtsAnnotation(range.item, start, end)
        }
    }
    annotatedText.getLinkAnnotations(0, annotatedText.length).forEach { range ->
        remapRange(indexMap, range.start, range.end)?.let { (start, end) ->
            when (val item = range.item) {
                is LinkAnnotation.Url -> builder.addLink(item, start, end)
                is LinkAnnotation.Clickable -> builder.addLink(item, start, end)
            }
        }
    }
    extensionRanges.forEach { range ->
        val item = range.item
        if (item is SpanStyle) {
            builder.addStyle(item, range.start, range.end)
        }
    }
    return builder.toAnnotatedString()
}

private fun consumeRemovedMarker(
    text: String,
    startIndex: Int,
    marker: String,
    indexMap: IntArray,
    outputLength: Int,
): Int {
    val endIndex = startIndex + marker.length
    for (cursor in startIndex + 1..endIndex) {
        indexMap[cursor] = outputLength
    }
    return endIndex
}

private fun restoreUnmatchedInlineMarkers(
    output: StringBuilder,
    markers: ArrayDeque<PendingInlineMarker>,
) {
    while (markers.isNotEmpty()) {
        val marker = markers.removeLast()
        output.replace(marker.start, marker.start + marker.placeholder.length, marker.literal)
    }
}

private fun remapRange(
    indexMap: IntArray,
    start: Int,
    end: Int,
): Pair<Int, Int>? {
    val remappedStart = indexMap[start]
    val remappedEnd = indexMap[end]
    return if (remappedStart >= remappedEnd) null else remappedStart to remappedEnd
}

private fun String.countRepeatedBackticks(startIndex: Int): Int {
    if (getOrNull(startIndex) != '`') return 0
    var length = 0
    while (getOrNull(startIndex + length) == '`') {
        length++
    }
    return length
}

private const val HIGHLIGHT_MARKER_LENGTH = 2
private const val UNDERLINE_OPEN_TAG_LENGTH = 3
private const val UNDERLINE_CLOSE_TAG_LENGTH = 4
