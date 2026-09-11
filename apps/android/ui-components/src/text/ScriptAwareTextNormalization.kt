package com.lomo.ui.text

import androidx.compose.ui.text.AnnotatedString

internal data class MemoDisplayTextState(
    var previousVisibleChar: Char? = null,
    var nextDoubleQuoteIsOpen: Boolean = true,
    var nextSingleQuoteIsOpen: Boolean = true,
    var chineseParenthesisDepth: Int = 0,
)

internal data class NormalizedChunk(
    val text: String,
    val nextIndex: Int,
)

internal fun AnnotatedString.Builder.appendNormalizedDisplayLiteral(
    source: CharSequence,
    state: MemoDisplayTextState,
) {
    appendNormalizedDisplayLiteral(
        builder = this,
        source = source,
        state = state,
        allowLeadingBoundaryFromState = false,
    )
}

internal fun AnnotatedString.Builder.appendBoundarySpaceIfNeeded(
    nextVisibleChar: Char?,
    state: MemoDisplayTextState,
) {
    if (
        nextVisibleChar == null ||
        !canInsertBoundarySpace(
            left = state.previousVisibleChar,
            right = nextVisibleChar,
        )
    ) {
        return
    }
    append(' ')
    state.previousVisibleChar = ' '
}

internal fun appendNormalizedDisplayLiteral(
    builder: Appendable,
    source: CharSequence,
    state: MemoDisplayTextState,
    allowLeadingBoundaryFromState: Boolean,
) {
    var previousVisibleChar = state.previousVisibleChar
    var index = 0

    while (index < source.length) {
        val rawChar = source[index]
        previousVisibleChar =
            appendBoundaryIfNeeded(
                builder = builder,
                previousVisibleChar = previousVisibleChar,
                rawChar = rawChar,
                shouldHandleBoundary = index > 0 || allowLeadingBoundaryFromState,
            )

        val chunk =
            consumeSpecialChunk(
                source = source,
                index = index,
                previousVisibleChar = previousVisibleChar,
                state = state,
            ) ?: normalizeCharacterChunk(
                source = source,
                index = index,
                previousVisibleChar = previousVisibleChar,
                state = state,
            )

        builder.append(chunk.text)
        previousVisibleChar = chunk.text.last()
        index = chunk.nextIndex
    }

    state.previousVisibleChar = previousVisibleChar
}

private fun appendBoundaryIfNeeded(
    builder: Appendable,
    previousVisibleChar: Char?,
    rawChar: Char,
    shouldHandleBoundary: Boolean,
): Char? {
    if (
        !shouldHandleBoundary ||
        !canInsertBoundarySpace(
            left = previousVisibleChar,
            right = rawChar,
        )
    ) {
        return previousVisibleChar
    }

    builder.append(' ')
    return ' '
}

private fun consumeSpecialChunk(
    source: CharSequence,
    index: Int,
    previousVisibleChar: Char?,
    state: MemoDisplayTextState,
): NormalizedChunk? =
    consumeDashRunChunk(
        source = source,
        index = index,
        previousVisibleChar = previousVisibleChar,
    ) ?: consumeRepeatedQuoteChunk(
        source = source,
        index = index,
        previousVisibleChar = previousVisibleChar,
        state = state,
    )
