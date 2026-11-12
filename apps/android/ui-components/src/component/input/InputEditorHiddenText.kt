package com.lomo.ui.component.input

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun rememberInputEditorHiddenText(sourceText: String, ranges: List<TextRange>): InputEditorHiddenText {
    val currentSource = rememberUpdatedState(sourceText)
    val currentRanges = rememberUpdatedState(ranges)
    // BasicTextField keys its transformed state and IME session on this object's identity.
    // Observe changing projection inputs without replacing the active session on every keystroke.
    return remember { InputEditorHiddenText(currentSource, currentRanges) }
}

/** Output-only metadata removal; Compose owns cursor, selection and IME offset mapping. */
class InputEditorHiddenText(private val sourceText: State<String>, private val ranges: State<List<TextRange>>) :
    OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        // A newer input snapshot stays raw until its owner render projection arrives.
        val source = sourceText.value
        val hiddenRanges = ranges.value
        if (asCharSequence().toString() != source) return
        validateHiddenRanges(source, hiddenRanges)
        hiddenRanges.asReversed().forEach { replace(it.start, it.end, "") }
    }
}

fun inputEditorVisibleText(sourceText: String, ranges: List<TextRange>): String {
    validateHiddenRanges(sourceText, ranges)
    return buildString {
        var previousEnd = 0
        ranges.forEach { range ->
            append(sourceText, previousEnd, range.start)
            previousEnd = range.end
        }
        append(sourceText, previousEnd, sourceText.length)
    }
}

/** Backspace, selection replacement and IME edits cannot leave half a hidden token behind. */
fun applyInputHiddenTextEdit(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    ranges: List<TextRange>,
): TextFieldValue {
    validateHiddenRanges(previous.text, ranges)
    if (previous.text == proposed.text) return proposed
    val prefix = previous.text.commonPrefixWith(proposed.text).length
    val suffix = previous.text.substring(prefix).commonSuffixWith(proposed.text.substring(prefix)).length
    val oldEnd = previous.text.length - suffix
    val affected = ranges.filter { it.start < oldEnd && it.end > prefix }
    if (affected.isEmpty()) {
        val inserted = proposed.text.substring(prefix, proposed.text.length - suffix)
        val afterAtom = ranges.any { it.end == prefix }
        if (afterAtom && !previous.text[prefix - 1].isWhitespace() &&
            inserted.isNotEmpty() && !inserted.first().isWhitespace()
        ) {
            fun shifted(offset: Int): Int = if (offset >= prefix) offset + 1 else offset
            return TextFieldValue(
                proposed.text.replaceRange(prefix, prefix, " "),
                TextRange(shifted(proposed.selection.start), shifted(proposed.selection.end)),
                proposed.composition?.let { TextRange(shifted(it.start), shifted(it.end)) },
            )
        }
        return proposed
    }
    val start = minOf(prefix, affected.first().start)
    val end = maxOf(oldEnd, affected.last().end)
    val inserted = proposed.text.substring(prefix, proposed.text.length - suffix)
    val text = previous.text.replaceRange(start, end, inserted)
    return TextFieldValue(text, TextRange(start + inserted.length))
}

fun removeInputTextRange(value: TextFieldValue, sourceText: String, range: TextRange): TextFieldValue {
    require(value.text == sourceText) { "Text removal requires the current editor snapshot" }
    validateHiddenRanges(sourceText, listOf(range))
    fun offset(position: Int): Int = when {
        position <= range.start -> position
        position <= range.end -> range.start
        else -> position - range.length
    }
    return TextFieldValue(
        text = sourceText.removeRange(range.start, range.end),
        selection = TextRange(offset(value.selection.start), offset(value.selection.end)),
    )
}

/** Coalesces adjacent hidden metadata lines while each badge retains its exact cancellation range. */
fun mergeInputHiddenRanges(ranges: List<TextRange>): ImmutableList<TextRange> {
    val merged = mutableListOf<TextRange>()
    ranges.sortedBy { it.start }.forEach { range ->
        require(!range.reversed && !range.collapsed && range.start >= 0)
        val previous = merged.lastOrNull()
        if (previous != null && range.start <= previous.end) {
            merged[merged.lastIndex] = TextRange(previous.start, maxOf(previous.end, range.end))
        } else {
            merged += range
        }
    }
    return merged.toImmutableList()
}

private fun validateHiddenRanges(text: String, ranges: List<TextRange>) {
    var previousEnd = 0
    ranges.forEach { range ->
        require(!range.reversed && !range.collapsed && range.start >= previousEnd && range.end <= text.length) {
            "Hidden text ranges must be ordered, disjoint and inside the source"
        }
        previousEnd = range.end
    }
}
