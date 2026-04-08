package com.lomo.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material Design 3 Typography Scale
// Reference: https://m3.material.io/styles/typography/type-scale-tokens

// Use system sans-serif as the primary app font.
// On Android 12+ this maps to a variable font pipeline with proper weight interpolation.
private val AppSansFamily = FontFamily.SansSerif
private const val HEADLINE_LARGE_WEIGHT = 450
private const val TITLE_LARGE_WEIGHT = 520
private const val TITLE_MEDIUM_WEIGHT = 560
private const val BODY_WEIGHT = 430
private const val LABEL_WEIGHT = 560
private const val MIN_FONT_WEIGHT = 1
private const val MAX_FONT_WEIGHT = 1000

val Typography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W400,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W400,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = 0.0.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W400,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.0.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(HEADLINE_LARGE_WEIGHT),
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W500,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.0.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W500,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(TITLE_LARGE_WEIGHT),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(TITLE_MEDIUM_WEIGHT),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight.W600,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(BODY_WEIGHT),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(BODY_WEIGHT),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(BODY_WEIGHT),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(LABEL_WEIGHT),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(LABEL_WEIGHT),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = AppSansFamily,
                fontWeight = FontWeight(LABEL_WEIGHT),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

private fun TextStyle.adjustWeight(adjustment: Int): TextStyle {
    if (adjustment == 0) return this
    val current = fontWeight ?: FontWeight.Normal
    val adjusted = (current.weight + adjustment).coerceIn(MIN_FONT_WEIGHT, MAX_FONT_WEIGHT)
    return copy(fontWeight = FontWeight(adjusted))
}

fun Typography.withSystemFontWeightAdjustment(adjustment: Int): Typography {
    val resolvedAdjustment =
        if (adjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) {
            0
        } else {
            adjustment
        }
    if (resolvedAdjustment == 0) return this

    return copy(
        displayLarge = displayLarge.adjustWeight(resolvedAdjustment),
        displayMedium = displayMedium.adjustWeight(resolvedAdjustment),
        displaySmall = displaySmall.adjustWeight(resolvedAdjustment),
        headlineLarge = headlineLarge.adjustWeight(resolvedAdjustment),
        headlineMedium = headlineMedium.adjustWeight(resolvedAdjustment),
        headlineSmall = headlineSmall.adjustWeight(resolvedAdjustment),
        titleLarge = titleLarge.adjustWeight(resolvedAdjustment),
        titleMedium = titleMedium.adjustWeight(resolvedAdjustment),
        titleSmall = titleSmall.adjustWeight(resolvedAdjustment),
        bodyLarge = bodyLarge.adjustWeight(resolvedAdjustment),
        bodyMedium = bodyMedium.adjustWeight(resolvedAdjustment),
        bodySmall = bodySmall.adjustWeight(resolvedAdjustment),
        labelLarge = labelLarge.adjustWeight(resolvedAdjustment),
        labelMedium = labelMedium.adjustWeight(resolvedAdjustment),
        labelSmall = labelSmall.adjustWeight(resolvedAdjustment),
    )
}
