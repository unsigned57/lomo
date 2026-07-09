package com.lomo.ui.text

import kotlin.math.ceil
import kotlin.math.min

internal data class ForcedSplitResult(
    val lines: List<NaturalLine>,
    val remainder: LayoutToken?,
)

internal data class NaturalLine(
    val tokens: List<LayoutToken>,
    val forcedBreak: Boolean,
    val endsWithHardBreak: Boolean,
    val hasEllipsis: Boolean = false,
)

internal data class LayoutToken(
    val source: String,
    val start: Int,
    val end: Int,
    val kind: MemoTextRunKind,
    val widthPx: Float,
    val hardBreak: Boolean,
) {
    val isWhitespace: Boolean
        get() = kind == MemoTextRunKind.Whitespace

    val debugText: String
        get() = source.substring(start, end)

    val startsWithClosingPunctuation: Boolean
        get() = Character.codePointAt(source, start).isClosingMemoPunctuation()

    val endsWithOpeningPunctuation: Boolean
        get() = Character.codePointBefore(source, end).isOpeningMemoPunctuation()
}

internal fun LayoutToken.widthWithLeadingBoundary(
    currentLineTokens: List<LayoutToken>,
    baseLetterSpacingPx: Float,
): Float =
    widthPx +
        if (currentLineTokens.isEmpty()) {
            0f
        } else {
            baseLetterSpacingPx
        }

internal fun List<LayoutToken>.singleOrWhole(): LayoutToken? =
    when (size) {
        0 -> null
        1 -> first()
        else -> {
            val start = first().start
            val end = last().end
            LayoutToken(
                source = first().source,
                start = start,
                end = end,
                kind = first().kind,
                widthPx = sumOf { it.widthPx.toDouble() }.toFloat(),
                hardBreak = false,
            )
        }
    }

internal fun trimTrailingWhitespace(tokens: MutableList<LayoutToken>) {
    while (tokens.lastOrNull()?.isWhitespace == true) {
        tokens.removeAt(tokens.lastIndex)
    }
}

internal fun ellipsisSpacingAfter(
    tokens: List<LayoutToken>,
    baseLetterSpacingPx: Float,
): Float =
    if (tokens.isEmpty()) {
        0f
    } else {
        baseLetterSpacingPx
    }

internal fun resolveMemoTextLayoutMaxLines(
    requestedMaxLines: Int,
    maxHeightPx: Int,
    lineHeightPx: Float,
): Int {
    if (requestedMaxLines <= 0 || maxHeightPx == Int.MAX_VALUE || lineHeightPx <= 0f) {
        return requestedMaxLines
    }
    val visibleLines = ceil(maxHeightPx / lineHeightPx).toInt().coerceAtLeast(1)
    return min(requestedMaxLines, visibleLines)
}

internal fun countCodePoints(
    text: String,
    start: Int,
    end: Int,
): Int {
    var count = 0
    var index = start
    while (index < end) {
        index += Character.charCount(Character.codePointAt(text, index))
        count += 1
    }
    return count
}

internal fun expansionKind(
    left: LayoutToken,
    right: LayoutToken,
): MemoTextExpansionSlotKind? =
    rawExpansionKind(left, right)?.takeUnless {
        left.kind == MemoTextRunKind.Punctuation ||
            right.kind == MemoTextRunKind.Punctuation ||
            left.endsWithOpeningPunctuation ||
            right.startsWithClosingPunctuation
    }

internal fun leftText(token: LayoutToken): String = token.debugText.takeLast(DEBUG_SIDE_TEXT_LIMIT)

internal fun rightText(token: LayoutToken): String = token.debugText.take(DEBUG_SIDE_TEXT_LIMIT)

private fun rawExpansionKind(
    left: LayoutToken,
    right: LayoutToken,
): MemoTextExpansionSlotKind? =
    when {
        left.isWhitespace -> MemoTextExpansionSlotKind.Space
        left.kind == MemoTextRunKind.Cjk && right.kind == MemoTextRunKind.Cjk ->
            MemoTextExpansionSlotKind.CjkCharacter
        left.isCjkLatinBoundaryWith(right) -> MemoTextExpansionSlotKind.CjkLatinBoundary
        else -> null
    }

private fun LayoutToken.isCjkLatinBoundaryWith(right: LayoutToken): Boolean =
    (kind == MemoTextRunKind.Cjk && right.kind == MemoTextRunKind.Latin) ||
        (kind == MemoTextRunKind.Latin && right.kind == MemoTextRunKind.Cjk)

private const val DEBUG_SIDE_TEXT_LIMIT = 8

internal const val MEMO_TEXT_ELLIPSIS = "\u2026"
