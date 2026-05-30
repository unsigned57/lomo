package com.lomo.app.feature.settings

import com.lomo.domain.model.asOpaqueArgb
import kotlin.math.abs

private const val HUE_DEGREES_FULL = 360f
private const val HUE_SECTOR_DEGREES = 60f
private const val RGB_MAX_INT = 255
private const val RGB_MAX_FLOAT = 255f
private const val ARGB_RED_SHIFT = 16
private const val ARGB_GREEN_SHIFT = 8
private const val ARGB_BYTE_MASK = 0xFF
private const val HSL_SECTOR_RED_YELLOW = 0
private const val HSL_SECTOR_YELLOW_GREEN = 1
private const val HSL_SECTOR_GREEN_CYAN = 2
private const val HSL_SECTOR_CYAN_BLUE = 3
private const val HSL_SECTOR_BLUE_MAGENTA = 4

internal const val COLOR_PICKER_RGB_MASK = 0xFFFFFF

/**
 * Converts an opaque ARGB int to a HSL triple [hue 0..360, saturation 0..1, lightness 0..1].
 * Pure function — safe for tests.
 */
internal fun argbToHsl(argb: Int): FloatArray {
    val r = ((argb shr ARGB_RED_SHIFT) and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val g = ((argb shr ARGB_GREEN_SHIFT) and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val b = (argb and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    val saturation = when {
        delta == 0f -> 0f
        lightness < 0.5f -> delta / (max + min)
        else -> delta / (2f - max - min)
    }
    val hue = when {
        delta == 0f -> 0f
        max == r -> HUE_SECTOR_DEGREES * (((g - b) / delta) % 6f)
        max == g -> HUE_SECTOR_DEGREES * (((b - r) / delta) + 2f)
        else -> HUE_SECTOR_DEGREES * (((r - g) / delta) + 4f)
    }
    val normalizedHue = if (hue < 0f) hue + HUE_DEGREES_FULL else hue
    return floatArrayOf(normalizedHue, saturation, lightness)
}

/**
 * Converts HSL ([hue 0..360, saturation 0..1, lightness 0..1]) to an opaque ARGB int.
 * Pure function — safe for tests.
 */
internal fun hslToOpaqueArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val hPrime = hue / HUE_SECTOR_DEGREES
    val x = c * (1f - abs((hPrime % 2f) - 1f))
    val m = lightness - c / 2f
    val (r1, g1, b1) = when (hPrime.toInt()) {
        HSL_SECTOR_RED_YELLOW -> Triple(c, x, 0f)
        HSL_SECTOR_YELLOW_GREEN -> Triple(x, c, 0f)
        HSL_SECTOR_GREEN_CYAN -> Triple(0f, c, x)
        HSL_SECTOR_CYAN_BLUE -> Triple(0f, x, c)
        HSL_SECTOR_BLUE_MAGENTA -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    val g = ((g1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    val b = ((b1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    return asOpaqueArgb((r shl ARGB_RED_SHIFT) or (g shl ARGB_GREEN_SHIFT) or b)
}
