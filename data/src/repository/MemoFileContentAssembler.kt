package com.lomo.data.repository

internal fun buildUpdatedMemoLines(
    newRawContent: String,
    timestampStr: String,
): List<String> {
    val contentLines = newRawContent.lines()
    return when {
        contentLines.size == 1 && contentLines.first().isEmpty() -> listOf("- $timestampStr")
        contentLines.isNotEmpty() ->
            buildList(contentLines.size) {
                add("- $timestampStr ${contentLines.first()}")
                for (index in 1 until contentLines.size) {
                    add(contentLines[index])
                }
            }
        else -> listOf("- $timestampStr")
    }
}

internal fun rebuildMemoContent(
    lines: List<String>,
    startIndex: Int,
    endIndex: Int,
    replacementLines: List<String>,
): String =
    buildString(
        lines.sumOf(String::length) +
            replacementLines.sumOf(String::length) +
            lines.size +
            replacementLines.size,
    ) {
        appendLineRange(lines, 0, startIndex)
        appendLineRange(replacementLines, 0, replacementLines.size)
        appendLineRange(lines, endIndex + 1, lines.size)
    }

private fun StringBuilder.appendLineRange(
    lines: List<String>,
    startIndex: Int,
    endIndexExclusive: Int,
) {
    for (index in startIndex until endIndexExclusive) {
        if (isNotEmpty()) {
            append('\n')
        }
        append(lines[index])
    }
}
