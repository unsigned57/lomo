package com.lomo.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private const val MIN_FONT_WEIGHT = 1
private const val MAX_FONT_WEIGHT = 1000

/** Material's regular and emphasized styles share the user's chosen font family. */
fun buildAppTypography(family: FontFamily): Typography = Typography(fontFamily = family)

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
        displayLargeEmphasized = displayLargeEmphasized.adjustWeight(resolvedAdjustment),
        displayMediumEmphasized = displayMediumEmphasized.adjustWeight(resolvedAdjustment),
        displaySmallEmphasized = displaySmallEmphasized.adjustWeight(resolvedAdjustment),
        headlineLargeEmphasized = headlineLargeEmphasized.adjustWeight(resolvedAdjustment),
        headlineMediumEmphasized = headlineMediumEmphasized.adjustWeight(resolvedAdjustment),
        headlineSmallEmphasized = headlineSmallEmphasized.adjustWeight(resolvedAdjustment),
        titleLargeEmphasized = titleLargeEmphasized.adjustWeight(resolvedAdjustment),
        titleMediumEmphasized = titleMediumEmphasized.adjustWeight(resolvedAdjustment),
        titleSmallEmphasized = titleSmallEmphasized.adjustWeight(resolvedAdjustment),
        bodyLargeEmphasized = bodyLargeEmphasized.adjustWeight(resolvedAdjustment),
        bodyMediumEmphasized = bodyMediumEmphasized.adjustWeight(resolvedAdjustment),
        bodySmallEmphasized = bodySmallEmphasized.adjustWeight(resolvedAdjustment),
        labelLargeEmphasized = labelLargeEmphasized.adjustWeight(resolvedAdjustment),
        labelMediumEmphasized = labelMediumEmphasized.adjustWeight(resolvedAdjustment),
        labelSmallEmphasized = labelSmallEmphasized.adjustWeight(resolvedAdjustment),
    )
}
