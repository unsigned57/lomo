package com.lomo.app.feature.settings

import android.os.Build
import com.lomo.domain.model.ColorSource
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

private const val HEX_COLOR_LENGTH = 6
private const val HEX_RADIX = 16
private const val OPAQUE_ALPHA_MASK = 0xFF000000.toInt()
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val COLOR_CHANNEL_MAX = 255.0
private const val SRGB_LINEAR_THRESHOLD = 0.04045
private const val SRGB_LINEAR_LOW_DIVISOR = 12.92
private const val SRGB_LINEAR_OFFSET = 0.055
private const val SRGB_LINEAR_SCALE = 1.055
private const val SRGB_LINEAR_GAMMA = 2.4
private const val LUMINANCE_RED_WEIGHT = 0.2126
private const val LUMINANCE_GREEN_WEIGHT = 0.7152
private const val LUMINANCE_BLUE_WEIGHT = 0.0722
private const val LIGHT_COLOR_LUMINANCE_THRESHOLD = 0.45
private const val PERCENT_SCALE = 100f
private const val HUE_DEGREES_MAX = 360f
private const val RANDOM_SEED_SATURATION_MIN = 0.48f
private const val RANDOM_SEED_SATURATION_MAX = 0.85f
private const val RANDOM_SEED_LIGHTNESS_MIN = 0.40f
private const val RANDOM_SEED_LIGHTNESS_MAX = 0.60f

internal sealed interface HexColorParseResult {
    data class Valid(val argb: Int) : HexColorParseResult

    data object Incomplete : HexColorParseResult

    data object Invalid : HexColorParseResult
}

internal fun parseHexColorInput(input: String): HexColorParseResult {
    val trimmed = input.trim()
    val normalized = if (trimmed.startsWith('#')) trimmed.drop(1) else trimmed
    if (normalized.isEmpty()) return HexColorParseResult.Incomplete
    if (normalized.length > HEX_COLOR_LENGTH || normalized.any { !it.isHexDigit() }) {
        return HexColorParseResult.Invalid
    }
    if (normalized.length < HEX_COLOR_LENGTH) return HexColorParseResult.Incomplete
    val rgb = normalized.toInt(HEX_RADIX)
    return HexColorParseResult.Valid(OPAQUE_ALPHA_MASK or rgb)
}

internal fun formatHexColor(argb: Int): String = "#%06X".format(Locale.ROOT, argb and COLOR_PICKER_RGB_MASK)

internal fun shouldUseDarkContentOnColor(argb: Int): Boolean {
    val red = srgbChannelToLinear(((argb shr RED_SHIFT) and CHANNEL_MASK) / COLOR_CHANNEL_MAX)
    val green = srgbChannelToLinear(((argb shr GREEN_SHIFT) and CHANNEL_MASK) / COLOR_CHANNEL_MAX)
    val blue = srgbChannelToLinear((argb and CHANNEL_MASK) / COLOR_CHANNEL_MAX)
    val luminance = LUMINANCE_RED_WEIGHT * red + LUMINANCE_GREEN_WEIGHT * green + LUMINANCE_BLUE_WEIGHT * blue
    return luminance > LIGHT_COLOR_LUMINANCE_THRESHOLD
}

internal fun isContrastAdjustable(colorSource: ColorSource, isPreviewingCustom: Boolean, sdkInt: Int): Boolean =
    isPreviewingCustom ||
        colorSource !is ColorSource.DynamicWallpaper ||
        sdkInt < Build.VERSION_CODES.S

internal fun isCustomSeedAlreadyApplied(activeSource: ColorSource, currentCustomSeedArgb: Int): Boolean =
    activeSource is ColorSource.CustomSeed &&
        (activeSource.argb and COLOR_PICKER_RGB_MASK) == (currentCustomSeedArgb and COLOR_PICKER_RGB_MASK)

internal fun generateRandomSeedHsl(random: Random = Random): FloatArray = floatArrayOf(
    random.nextFloat() * HUE_DEGREES_MAX,
    RANDOM_SEED_SATURATION_MIN + random.nextFloat() * (RANDOM_SEED_SATURATION_MAX - RANDOM_SEED_SATURATION_MIN),
    RANDOM_SEED_LIGHTNESS_MIN + random.nextFloat() * (RANDOM_SEED_LIGHTNESS_MAX - RANDOM_SEED_LIGHTNESS_MIN),
)

internal fun formatHueDegrees(hue: Float): String = "${hue.roundToInt()}°"

internal fun formatPercent(fraction: Float): String = "${(fraction * PERCENT_SCALE).roundToInt()}%"

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun srgbChannelToLinear(channel: Double): Double = if (channel <= SRGB_LINEAR_THRESHOLD) {
    channel / SRGB_LINEAR_LOW_DIVISOR
} else {
    ((channel + SRGB_LINEAR_OFFSET) / SRGB_LINEAR_SCALE).pow(SRGB_LINEAR_GAMMA)
}
