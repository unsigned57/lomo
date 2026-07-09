package com.lomo.ui.text

internal fun shouldConvertDashRun(
    source: CharSequence,
    startIndex: Int,
    runLength: Int,
    previousVisibleChar: Char?,
): Boolean {
    if (runLength < DASH_RUN_THRESHOLD) return false
    val nextAfterRun = source.nextNonWhitespaceChar(startIndex + runLength - 1)
    return previousVisibleChar?.isChineseTypographyContextChar() == true ||
        nextAfterRun?.isChineseTypographyContextChar() == true
}

internal fun shouldCollapseRepeatedQuoteRun(
    source: CharSequence,
    startIndex: Int,
    runLength: Int,
    previousVisibleChar: Char?,
): Boolean {
    if (runLength < REPEATED_QUOTE_THRESHOLD) return false
    val nextAfterRun = source.nextNonWhitespaceChar(startIndex + runLength - 1)
    return previousVisibleChar?.isChineseTypographyContextChar() == true ||
        nextAfterRun?.isChineseTypographyContextChar() == true
}

internal fun shouldInsertCjkLatinBoundarySpace(
    left: Char,
    right: Char,
): Boolean =
    (left.isCjkScript() && right.isLatinAlphaNumeric()) ||
        (left.isLatinAlphaNumeric() && right.isCjkScript())

internal fun canInsertBoundarySpace(
    left: Char?,
    right: Char,
): Boolean =
    left != null &&
        !left.isWhitespace() &&
        !right.isWhitespace() &&
        shouldInsertCjkLatinBoundarySpace(left, right)

internal fun CharSequence.previousNonWhitespaceChar(index: Int): Char? {
    for (cursor in index - 1 downTo 0) {
        val char = this[cursor]
        if (!char.isWhitespace()) return char
    }
    return null
}

internal fun CharSequence.nextNonWhitespaceChar(index: Int): Char? {
    for (cursor in index + 1 until length) {
        val char = this[cursor]
        if (!char.isWhitespace()) return char
    }
    return null
}

internal fun CharSequence.findMatchingCloseParenthesis(openIndex: Int): Int? {
    var depth = 0
    for (cursor in openIndex until length) {
        when (this[cursor]) {
            OPEN_PAREN -> depth++
            CLOSE_PAREN -> {
                depth--
                if (depth == 0) return cursor
            }
        }
    }
    return null
}

internal fun CharSequence.hyphenRunLengthFrom(startIndex: Int): Int {
    var count = 0
    var cursor = startIndex
    while (cursor < length && this[cursor] == HYPHEN_MINUS) {
        count++
        cursor++
    }
    return count
}

internal fun CharSequence.sameCharRunLengthFrom(
    startIndex: Int,
    char: Char,
): Int {
    var count = 0
    var cursor = startIndex
    while (cursor < length && this[cursor] == char) {
        count++
        cursor++
    }
    return count
}
