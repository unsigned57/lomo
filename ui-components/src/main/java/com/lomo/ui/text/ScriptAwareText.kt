package com.lomo.ui.text

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

private const val NORMALIZED_TEXT_BUFFER_PADDING = 8

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

    val hasCjk = cjkCount > 0
    val cjkOnly = alphaNumericCount == 0
    return hasCjk && (cjkOnly || cjkCount >= alphaNumericCount)
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
    if (isEmpty() || !containsCjkScript()) return toString()

    val source = toString()
    val out = StringBuilder(source.length + NORMALIZED_TEXT_BUFFER_PADDING)
    var previousOut: Char? = null

    source.forEachIndexed { index, rawChar ->
        val current = normalizeHalfWidthPunctuation(rawChar)

        val left = previousOut
        val canInsertBoundarySpace =
            left != null && shouldInsertCjkLatinBoundarySpace(left, current)
        val hasBoundaryWhitespace =
            left == ' ' || left == '\n' || left == '\t' || current == ' '
        if (
            canInsertBoundarySpace &&
            !hasBoundaryWhitespace
        ) {
            out.append(' ')
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

private val ZH_LOCALE_LIST = LocaleList(Locale("zh-CN"))
private val JA_LOCALE_LIST = LocaleList(Locale("ja-JP"))
private val KO_LOCALE_LIST = LocaleList(Locale("ko-KR"))
