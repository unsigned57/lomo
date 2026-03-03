package com.lomo.ui.text

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

fun CharSequence.isCjkDominant(): Boolean {
    var cjkCount = 0
    var alphaNumericCount = 0

    forEach { char ->
        if (char.isWhitespace()) return@forEach
        if (char.isCjkScript()) {
            cjkCount++
        } else if (char.isLetterOrDigit()) {
            alphaNumericCount++
        }
    }

    if (cjkCount == 0) return false
    if (alphaNumericCount == 0) return true
    return cjkCount >= alphaNumericCount
}

fun CharSequence.scriptAwareTextAlign(): TextAlign = if (containsCjkScript()) TextAlign.Justify else TextAlign.Start

fun TextStyle.scriptAwareFor(text: CharSequence): TextStyle {
    val cjkAware = text.containsCjkScript()
    return copy(
        // M3 default letter spacing is tuned for latin scripts and looks loose in CJK paragraphs.
        letterSpacing = if (cjkAware) 0.sp else letterSpacing,
        lineBreak =
            if (cjkAware) {
                LineBreak(
                    strategy = LineBreak.Strategy.HighQuality,
                    strictness = LineBreak.Strictness.Loose,
                    wordBreak = LineBreak.WordBreak.Phrase,
                )
            } else {
                LineBreak.Paragraph
            },
        hyphens = if (cjkAware) Hyphens.None else Hyphens.Auto,
        localeList = if (cjkAware) text.preferredCjkLocaleList() else localeList,
    )
}

/**
 * Improves mixed CJK/Latin composition for paragraph layout:
 * - Insert boundary spaces between CJK and Latin words (e.g. "中文ABC" -> "中文 ABC").
 * - Convert half-width punctuation to full-width when adjacent to CJK.
 */
fun CharSequence.normalizeCjkMixedSpacingForDisplay(): String {
    if (!containsCjkScript()) return toString()
    if (isEmpty()) return ""

    val source = toString()
    val out = StringBuilder(source.length + 8)
    var previousOut: Char? = null

    source.forEachIndexed { index, rawChar ->
        val current = normalizeHalfWidthPunctuation(rawChar)

        val left = previousOut
        if (left != null && shouldInsertCjkLatinBoundarySpace(left, current)) {
            if (left != ' ' && left != '\n' && left != '\t' && current != ' ') {
                out.append(' ')
            }
        }

        out.append(current)
        previousOut = current
    }

    return out.toString()
}

private fun CharSequence.containsCjkScript(): Boolean = any { it.isCjkScript() }

private fun CharSequence.preferredCjkLocaleList(): LocaleList =
    when {
        any { it.isJapaneseScript() } -> JA_LOCALE_LIST
        any { it.isKoreanScript() } -> KO_LOCALE_LIST
        else -> ZH_LOCALE_LIST
    }

private fun Char.isCjkScript(): Boolean {
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

private fun Char.isJapaneseScript(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
}

private fun Char.isKoreanScript(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

private fun normalizeHalfWidthPunctuation(char: Char): Char {
    val mapped = char.toFullWidthPunctuationOrNull() ?: return char
    return mapped
}

private fun shouldInsertCjkLatinBoundarySpace(
    left: Char,
    right: Char,
): Boolean =
    (left.isCjkScript() && right.isLatinAlphaNumeric()) ||
        (left.isLatinAlphaNumeric() && right.isCjkScript())

private fun Char.isLatinAlphaNumeric(): Boolean = isLetterOrDigit() && !isCjkScript()

private fun Char.toFullWidthPunctuationOrNull(): Char? {
    if (code !in ASCII_PUNCTUATION_START..ASCII_PUNCTUATION_END) return null
    if (isLetterOrDigit()) return null
    return (code + FULLWIDTH_ASCII_OFFSET).toChar()
}

private val ZH_LOCALE_LIST = LocaleList(Locale("zh-CN"))
private val JA_LOCALE_LIST = LocaleList(Locale("ja-JP"))
private val KO_LOCALE_LIST = LocaleList(Locale("ko-KR"))

private const val ASCII_PUNCTUATION_START = 0x21
private const val ASCII_PUNCTUATION_END = 0x7E
private const val FULLWIDTH_ASCII_OFFSET = 0xFEE0
