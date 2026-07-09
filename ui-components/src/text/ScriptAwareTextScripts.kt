package com.lomo.ui.text

import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList

private val ZH_LOCALE_LIST = LocaleList(Locale("zh-CN"))
private val JA_LOCALE_LIST = LocaleList(Locale("ja-JP"))
private val KO_LOCALE_LIST = LocaleList(Locale("ko-KR"))

private val CHINESE_TYPOGRAPHY_CONTEXT_CHARS =
    setOf(
        '，',
        '。',
        '：',
        '；',
        '！',
        '？',
        '、',
        '“',
        '”',
        '‘',
        '’',
        '（',
        '）',
        '—',
    )

internal fun CharSequence.containsCjkScript(): Boolean = any { it.isCjkScript() }

internal fun CharSequence.shouldUseCjkJustify(): Boolean =
    containsCjkScript() &&
        !containsLatinScriptLetter()

internal fun CharSequence.preferredCjkLocaleList(): LocaleList =
    when {
        any { it.isJapaneseScript() } -> JA_LOCALE_LIST
        any { it.isKoreanScript() } -> KO_LOCALE_LIST
        else -> ZH_LOCALE_LIST
    }

internal fun Char.isCjkScript(): Boolean {
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

internal fun Char.isLatinAlphaNumeric(): Boolean = isLetterOrDigit() && !isCjkScript()

internal fun CharSequence.containsLatinScriptLetter(): Boolean = any { it.isLatinScriptLetter() }

internal fun Char.isLatinScriptLetter(): Boolean {
    if (!isLetter()) return false
    val script = Character.UnicodeScript.of(code)
    return script == Character.UnicodeScript.LATIN
}

internal fun Char.isChineseTypographyContextChar(): Boolean =
    isCjkScript() || this in CHINESE_TYPOGRAPHY_CONTEXT_CHARS
