package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private const val DRAFT_ORDERED_DELIMITER_GROUP = 5
private const val DRAFT_SPACING_GROUP = 6
private const val MAX_ORDERED_MARKER_NUMBER = 999_999_999L

private val draftListMarker = Regex("""^([\t ]*)(?:([-*+])([\t ]+\[[ xX]])?|([0-9]{1,9})([.)]))([\t ]+)""")
private val draftFence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

/** A single draft transaction: continue/exit the item, renumber siblings, and move the caret.
 * This edits the input buffer only; preview and committed Markdown keep the Rust semantic owner.
 */
internal fun autoContinueListMarker(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
    if (newValue.composition != null || !newValue.selection.collapsed) return null
    val oldText = oldValue.text
    val text = newValue.text
    val cursor = newValue.selection.start
    val insertion = cursor - 1
    if (text.length != oldText.length + 1 || insertion !in text.indices || text[insertion] != '\n') return null
    if (text.removeRange(insertion, cursor) != oldText) return null
    val lineStart = oldText.lastIndexOf('\n', insertion - 1) + 1
    if (insideDraftCodeFence(oldText.substring(0, lineStart))) return null
    val line = oldText.substring(lineStart, insertion)
    val marker = draftListMarker.matchAt(line, 0) ?: return null
    if (line.substring(marker.value.length).isBlank()) {
        return TextFieldValue(text.removeRange(lineStart, insertion), TextRange(lineStart + 1))
    }
    val indent = marker.groupValues[1]
    val bullet = marker.groupValues[2]
    val checkbox = marker.groupValues[3]
    val number = marker.groupValues[4]
    val delimiter = marker.groupValues[DRAFT_ORDERED_DELIMITER_GROUP]
    val spacing = marker.groupValues[DRAFT_SPACING_GROUP]
    val nextNumber = if (number.isEmpty()) null else number.toLong() + 1L
    if (nextNumber != null && nextNumber > MAX_ORDERED_MARKER_NUMBER) return null
    val nextMarker = if (nextNumber != null) {
        "$indent$nextNumber$delimiter$spacing"
    } else {
        indent + bullet + (if (checkbox.isEmpty()) "" else " [ ]") + spacing
    }
    val suffix = text.substring(cursor)
    val updatedSuffix = if (nextNumber == null) suffix else renumberDraftSiblings(suffix, indent, delimiter, nextNumber)
    return TextFieldValue(text.substring(0, cursor) + nextMarker + updatedSuffix, TextRange(cursor + nextMarker.length))
}

private fun insideDraftCodeFence(prefix: String): Boolean {
    var opening: String? = null
    prefix.lineSequence().forEach { line ->
        val match = draftFence.matchEntire(line)
        if (match != null) {
            val fence = match.groupValues[1]
            val current = opening
            if (current == null) {
                if (fence.first() != '`' || !match.groupValues[2].contains('`')) opening = fence
            } else if (
                fence.first() == current.first() && fence.length >= current.length &&
                match.groupValues[2].isBlank()
            ) {
                opening = null
            }
        }
    }
    return opening != null
}

private fun renumberDraftSiblings(suffix: String, indent: String, delimiter: String, insertedNumber: Long): String {
    var number = insertedNumber
    var inList = true
    return suffix.split('\n').mapIndexed { index, line ->
        // The first fragment is the split item's remainder, not a subsequent sibling.
        if (index == 0 || !inList || line.isBlank()) return@mapIndexed line
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.length > indent.length) return@mapIndexed line
        val marker = draftListMarker.matchAt(line, 0)
        if (
            marker == null || marker.groupValues[1] != indent ||
            marker.groupValues[DRAFT_ORDERED_DELIMITER_GROUP] != delimiter
        ) {
            inList = false
            return@mapIndexed line
        }
        number += 1L
        val digits = marker.groups[4] ?: error("Ordered draft marker has no number")
        line.replaceRange(digits.range, number.toString())
    }.joinToString("\n")
}
