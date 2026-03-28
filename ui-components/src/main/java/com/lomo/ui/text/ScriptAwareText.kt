package com.lomo.ui.text

import android.text.Layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineHeightStyle
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

    val hasCjk = cjkCount > 0
    val cjkOnly = alphaNumericCount == 0
    return hasCjk && (cjkOnly || cjkCount >= alphaNumericCount)
}

fun CharSequence.scriptAwareTextAlign(): TextAlign =
    if (shouldUseCjkJustify()) TextAlign.Justify else TextAlign.Start

fun CharSequence.shouldUsePlatformCjkJustification(): Boolean = shouldUseCjkJustify()

fun CharSequence.platformJustificationMode(): Int = resolveMemoParagraphLayoutPolicy(this).justificationMode

fun TextStyle.scriptAwareFor(text: CharSequence): TextStyle {
    val cjkAware = text.containsCjkScript()
    return copy(
        // M3 default letter spacing is tuned for latin scripts and looks loose in CJK paragraphs.
        letterSpacing = if (cjkAware) 0.sp else letterSpacing,
        // Keep Compose text metrics closer to the platform CJK TextView path used by pure-Chinese paragraphs.
        platformStyle = if (cjkAware) PlatformTextStyle(includeFontPadding = false) else platformStyle,
        lineHeightStyle =
            if (cjkAware) {
                LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                )
            } else {
                lineHeightStyle
            },
        // Compose's custom CJK line-break presets interact poorly with punctuation such as quotes and colons.
        // Keep justification eligibility separate from line-break tuning and stay on the default paragraph path.
        lineBreak = LineBreak.Paragraph,
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

    val out = StringBuilder(length + NORMALIZED_TEXT_BUFFER_PADDING)
    appendNormalizedDisplayLiteral(
        builder = out,
        source = this,
        state = MemoDisplayTextState(),
        allowLeadingBoundaryFromState = true,
    )
    return out.toString()
}
