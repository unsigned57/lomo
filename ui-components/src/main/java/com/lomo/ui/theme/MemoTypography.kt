package com.lomo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private const val BASELINE_LETTER_SPACING = 0.1f
private const val LETTER_SPACING_STEP = 1.0f

private val MemoEditorChromeLineHeightStyle =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.None,
    )

private val MemoEditorChromePlatformStyle = PlatformTextStyle(includeFontPadding = false)

private fun scaledLetterSpacing(scale: Float) =
    (BASELINE_LETTER_SPACING + LETTER_SPACING_STEP * (scale - 1.0f)).sp

fun Typography.memoBodyTextStyle(scales: TypographyScales = TypographyScales()): TextStyle =
    bodyMedium.copy(
        fontSize = bodyMedium.fontSize * scales.fontSizeScale,
        lineHeight = 16.sp * scales.fontSizeScale * scales.lineHeightScale,
        letterSpacing = scaledLetterSpacing(scales.letterSpacingScale),
    )

@Composable
fun Typography.memoBodyTextStyle(): TextStyle = memoBodyTextStyle(currentTypographyScales())

fun Typography.memoSummaryTextStyle(scales: TypographyScales = TypographyScales()): TextStyle =
    bodyMedium.copy(
        fontSize = bodyMedium.fontSize * scales.fontSizeScale,
        lineHeight = 20.sp * scales.fontSizeScale * scales.lineHeightScale,
        letterSpacing = scaledLetterSpacing(scales.letterSpacingScale),
    )

@Composable
fun Typography.memoSummaryTextStyle(): TextStyle = memoSummaryTextStyle(currentTypographyScales())

fun Typography.memoEditorTextStyle(scales: TypographyScales = TypographyScales()): TextStyle =
    memoBodyTextStyle(scales).copy(
        lineHeightStyle = MemoEditorChromeLineHeightStyle,
        platformStyle = MemoEditorChromePlatformStyle,
    )

@Composable
fun Typography.memoEditorTextStyle(): TextStyle = memoEditorTextStyle(currentTypographyScales())

fun Typography.memoHintTextStyle(scales: TypographyScales = TypographyScales()): TextStyle =
    bodyMedium.copy(
        fontSize = bodyMedium.fontSize * scales.fontSizeScale,
        lineHeight = 16.sp * scales.fontSizeScale * scales.lineHeightScale,
        letterSpacing = scaledLetterSpacing(scales.letterSpacingScale),
        lineHeightStyle = MemoEditorChromeLineHeightStyle,
        platformStyle = MemoEditorChromePlatformStyle,
    )

@Composable
fun Typography.memoHintTextStyle(): TextStyle = memoHintTextStyle(currentTypographyScales())

fun Typography.memoListTextStyle(scales: TypographyScales = TypographyScales()): TextStyle =
    memoBodyTextStyle(scales)

@Composable
fun Typography.memoListTextStyle(): TextStyle = memoListTextStyle(currentTypographyScales())
