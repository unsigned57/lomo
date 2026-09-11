package com.lomo.ui.theme

import android.content.res.Configuration
import android.graphics.Typeface as PlatformTypeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

// Material Design 3 Typography Scale
// Reference: https://m3.material.io/styles/typography/type-scale-tokens

private const val HEADLINE_LARGE_WEIGHT = 450
private const val TITLE_LARGE_WEIGHT = 520
private const val TITLE_MEDIUM_WEIGHT = 560
private const val BODY_WEIGHT = 430
private const val LABEL_WEIGHT = 560
private const val MIN_FONT_WEIGHT = 1
private const val MAX_FONT_WEIGHT = 1000

/**
 * Builds the app's [Typography] bound to [family]. Pass [FontFamily.SansSerif] for the system
 * default (variable-font pipeline on Android 12+) or a [FontFamily] wrapping a user-imported font.
 *
 * Replaces the previously hardcoded `val Typography` constant. There is no parallel "raw constant"
 * path — every consumer goes through `LomoTheme`, which calls this with the resolved family.
 */
fun buildAppTypography(family: FontFamily): Typography =
    Typography(
        displayLarge = displayStyle(family, sp = 57, lh = 64, ls = -0.25),
        displayMedium = displayStyle(family, sp = 45, lh = 52, ls = 0.0),
        displaySmall = displayStyle(family, sp = 36, lh = 44, ls = 0.0),
        headlineLarge = headlineStyle(family, weight = HEADLINE_LARGE_WEIGHT, sp = 32, lh = 40),
        headlineMedium = headlineStyle(family, weight = FontWeight.W500.weight, sp = 28, lh = 36),
        headlineSmall = headlineStyle(family, weight = FontWeight.W500.weight, sp = 24, lh = 32),
        titleLarge = titleStyle(family, weight = TITLE_LARGE_WEIGHT, sp = 22, lh = 28, ls = 0.0),
        titleMedium = titleStyle(family, weight = TITLE_MEDIUM_WEIGHT, sp = 16, lh = 24, ls = 0.15),
        titleSmall = titleStyle(family, weight = FontWeight.W600.weight, sp = 14, lh = 20, ls = 0.1),
        bodyLarge = bodyStyle(family, weight = BODY_WEIGHT, sp = 16, lh = 24, ls = 0.5),
        bodyMedium = bodyStyle(family, weight = BODY_WEIGHT, sp = 14, lh = 20, ls = 0.1),
        bodySmall = bodyStyle(family, weight = BODY_WEIGHT, sp = 12, lh = 16, ls = 0.4),
        labelLarge = labelStyle(family, weight = LABEL_WEIGHT, sp = 14, lh = 20, ls = 0.1),
        labelMedium = labelStyle(family, weight = LABEL_WEIGHT, sp = 12, lh = 16, ls = 0.5),
        labelSmall = labelStyle(family, weight = LABEL_WEIGHT, sp = 11, lh = 16, ls = 0.5),
    )

private fun displayStyle(family: FontFamily, sp: Int, lh: Int, ls: Double): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.W400,
        fontSize = sp.sp,
        lineHeight = lh.sp,
        letterSpacing = ls.sp,
    )

private fun headlineStyle(family: FontFamily, weight: Int, sp: Int, lh: Int): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = sp.sp,
        lineHeight = lh.sp,
        letterSpacing = 0.0.sp,
    )

private fun titleStyle(family: FontFamily, weight: Int, sp: Int, lh: Int, ls: Double): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = sp.sp,
        lineHeight = lh.sp,
        letterSpacing = ls.sp,
    )

private fun bodyStyle(family: FontFamily, weight: Int, sp: Int, lh: Int, ls: Double): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = sp.sp,
        lineHeight = lh.sp,
        letterSpacing = ls.sp,
    )

private fun labelStyle(family: FontFamily, weight: Int, sp: Int, lh: Int, ls: Double): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = sp.sp,
        lineHeight = lh.sp,
        letterSpacing = ls.sp,
    )

/**
 * Resolves a custom-font absolute path (typically returned by `CustomFontStore.resolveFontPath`)
 * into a Compose [FontFamily]. Falls back to [FontFamily.SansSerif] when the path is null, blank,
 * or points to a missing file — matching the documented "missing font = system default" contract.
 */
fun resolveAppFontFamily(customFontPath: String?): FontFamily {
    if (customFontPath.isNullOrBlank()) return FontFamily.SansSerif
    val file = File(customFontPath)
    if (!file.exists()) return FontFamily.SansSerif
    return FontFamily(Font(file = file))
}

/**
 * Resolves a custom-font path to a raw Android [PlatformTypeface] for non-Compose render surfaces
 * (e.g. the share-card bitmap renderer). Returns null when the path is null, blank, missing, or
 * cannot be parsed as a font file.
 */
fun resolveCustomCanvasTypeface(customFontPath: String?): PlatformTypeface? {
    if (customFontPath.isNullOrBlank()) return null
    val file = File(customFontPath)
    if (!file.exists()) return null
    // behavior-contract: silent-result-ok: malformed font file → fall back to platform default Typeface
    return runCatching { PlatformTypeface.createFromFile(file) }.getOrNull()
}

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
