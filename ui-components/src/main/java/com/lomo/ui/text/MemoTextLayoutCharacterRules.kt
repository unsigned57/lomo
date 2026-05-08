package com.lomo.ui.text

internal fun Int.isCjkLayoutCodePoint(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

internal fun Int.isLatinMemoTextCodePoint(): Boolean {
    if (this.toChar() in LATIN_MEMO_TOKEN_CONNECTORS) return true
    val script = Character.UnicodeScript.of(this)
    return script == Character.UnicodeScript.LATIN || Character.isDigit(this)
}

internal fun Int.isMemoLayoutPunctuation(): Boolean =
    isOpeningMemoPunctuation() ||
        isClosingMemoPunctuation() ||
        this.toChar() in NEUTRAL_MEMO_PUNCTUATION

internal fun Int.isOpeningMemoPunctuation(): Boolean = this.toChar() in OPENING_MEMO_PUNCTUATION

internal fun Int.isClosingMemoPunctuation(): Boolean = this.toChar() in CLOSING_MEMO_PUNCTUATION

private val LATIN_MEMO_TOKEN_CONNECTORS = setOf('_', '-', '/', '.', ':')
private val OPENING_MEMO_PUNCTUATION = setOf('「', '『', '“', '‘', '（', '《', '〈', '【', '〔', '(')
private val CLOSING_MEMO_PUNCTUATION =
    setOf(
        '」',
        '』',
        '”',
        '’',
        '）',
        '》',
        '〉',
        '】',
        '〕',
        ')',
        '，',
        '。',
        '、',
        '；',
        '：',
        '！',
        '？',
    )
private val NEUTRAL_MEMO_PUNCTUATION = setOf(',', '.', ';', ':', '!', '?')
