package com.lomo.ui.text

private val FULL_WIDTH_PUNCTUATION_MAP =
    mapOf(
        ',' to '，',
        '.' to '。',
        ':' to '：',
        ';' to '；',
        '/' to '／',
        '!' to '！',
        '?' to '？',
    )

internal fun consumeDashRunChunk(
    source: CharSequence,
    index: Int,
    previousVisibleChar: Char?,
): NormalizedChunk? {
    val runLength = source.hyphenRunLengthFrom(index)
    return if (source[index] == HYPHEN_MINUS && runLength >= DASH_RUN_THRESHOLD) {
        val normalizedRun =
            if (
                shouldConvertDashRun(
                    source = source,
                    startIndex = index,
                    runLength = runLength,
                    previousVisibleChar = previousVisibleChar,
                )
            ) {
                EM_DASH.toString().repeat(runLength)
            } else {
                HYPHEN_MINUS.toString().repeat(runLength)
            }

        NormalizedChunk(
            text = normalizedRun,
            nextIndex = index + runLength,
        )
    } else {
        null
    }
}

internal fun consumeRepeatedQuoteChunk(
    source: CharSequence,
    index: Int,
    previousVisibleChar: Char?,
    state: MemoDisplayTextState,
): NormalizedChunk? {
    val rawChar = source[index]
    val runLength = source.sameCharRunLengthFrom(index, rawChar)
    val isRepeatedQuoteRun =
        (rawChar == DOUBLE_QUOTE || rawChar == SINGLE_QUOTE) &&
            runLength >= REPEATED_QUOTE_THRESHOLD &&
            shouldCollapseRepeatedQuoteRun(
                source = source,
                startIndex = index,
                runLength = runLength,
                previousVisibleChar = previousVisibleChar,
            )

    return if (isRepeatedQuoteRun) {
        val normalizedQuote =
            if (rawChar == DOUBLE_QUOTE) {
                normalizeDoubleQuote(state)
            } else {
                normalizeSingleQuote(
                    source = source,
                    index = index,
                    state = state,
                )
            }

        NormalizedChunk(
            text = normalizedQuote.toString(),
            nextIndex = index + runLength,
        )
    } else {
        null
    }
}

internal fun normalizeCharacterChunk(
    source: CharSequence,
    index: Int,
    previousVisibleChar: Char?,
    state: MemoDisplayTextState,
): NormalizedChunk {
    val rawChar = source[index]
    val normalizedChar =
        when (rawChar) {
            DOUBLE_QUOTE ->
                normalizeDoubleQuote(state)
            SINGLE_QUOTE ->
                normalizeSingleQuote(
                    source = source,
                    index = index,
                    state = state,
                )
            OPEN_PAREN ->
                normalizeOpenParenthesis(
                    source = source,
                    index = index,
                    previousVisibleChar = previousVisibleChar,
                    state = state,
                )
            CLOSE_PAREN ->
                normalizeCloseParenthesis(state)
            else ->
                normalizeHalfWidthPunctuation(
                    source = source,
                    index = index,
                    rawChar = rawChar,
                )
        }

    return NormalizedChunk(
        text = normalizedChar.toString(),
        nextIndex = index + 1,
    )
}

private fun normalizeDoubleQuote(state: MemoDisplayTextState): Char =
    if (state.nextDoubleQuoteIsOpen) {
        state.nextDoubleQuoteIsOpen = false
        LEFT_DOUBLE_QUOTE
    } else {
        state.nextDoubleQuoteIsOpen = true
        RIGHT_DOUBLE_QUOTE
    }

private fun normalizeSingleQuote(
    source: CharSequence,
    index: Int,
    state: MemoDisplayTextState,
): Char {
    val leftImmediate = source.getOrNull(index - 1)
    val rightImmediate = source.getOrNull(index + 1)
    if (leftImmediate?.isLatinAlphaNumeric() == true && rightImmediate?.isLatinAlphaNumeric() == true) {
        return SINGLE_QUOTE
    }
    return if (state.nextSingleQuoteIsOpen) {
        state.nextSingleQuoteIsOpen = false
        LEFT_SINGLE_QUOTE
    } else {
        state.nextSingleQuoteIsOpen = true
        RIGHT_SINGLE_QUOTE
    }
}

private fun normalizeOpenParenthesis(
    source: CharSequence,
    index: Int,
    previousVisibleChar: Char?,
    state: MemoDisplayTextState,
): Char {
    val matchingCloseIndex = source.findMatchingCloseParenthesis(index)
    val nextAfterClose = matchingCloseIndex?.let(source::nextNonWhitespaceChar)
    val shouldConvert =
        previousVisibleChar?.isChineseTypographyContextChar() == true ||
            nextAfterClose?.isChineseTypographyContextChar() == true
    return if (shouldConvert) {
        state.chineseParenthesisDepth += 1
        FULLWIDTH_OPEN_PAREN
    } else {
        OPEN_PAREN
    }
}

private fun normalizeCloseParenthesis(state: MemoDisplayTextState): Char =
    if (state.chineseParenthesisDepth > 0) {
        state.chineseParenthesisDepth -= 1
        FULLWIDTH_CLOSE_PAREN
    } else {
        CLOSE_PAREN
    }

private fun normalizeHalfWidthPunctuation(
    source: CharSequence,
    index: Int,
    rawChar: Char,
): Char {
    val mapped = FULL_WIDTH_PUNCTUATION_MAP[rawChar] ?: return rawChar
    val left = source.previousNonWhitespaceChar(index)
    val right = source.nextNonWhitespaceChar(index)
    return if (left?.isCjkScript() == true || right?.isCjkScript() == true) mapped else rawChar
}
