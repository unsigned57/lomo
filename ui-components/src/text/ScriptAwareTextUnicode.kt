package com.lomo.ui.text

internal fun Char.isJapaneseScript(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA
}

internal fun Char.isKoreanScript(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

internal fun Char.toFullWidthPunctuationOrNull(): Char? {
    val isAsciiPunctuation = code in ASCII_PUNCTUATION_START..ASCII_PUNCTUATION_END
    return if (isAsciiPunctuation && !isLetterOrDigit()) {
        (code + FULLWIDTH_ASCII_OFFSET).toChar()
    } else {
        null
    }
}

private const val ASCII_PUNCTUATION_START = 0x21
private const val ASCII_PUNCTUATION_END = 0x7E
private const val FULLWIDTH_ASCII_OFFSET = 0xFEE0
