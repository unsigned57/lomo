package com.lomo.app.feature.settings

import com.lomo.domain.model.CustomFontVariationResolver
import com.lomo.domain.model.FontVariationAxis
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val AXIS_PRIORITY_WGHT = 0
private const val AXIS_PRIORITY_WDTH = 1
private const val AXIS_PRIORITY_OPSZ = 2
private const val AXIS_PRIORITY_SLNT = 3
private const val AXIS_PRIORITY_ITAL = 4
private const val AXIS_PRIORITY_GRAD = 5
private const val AXIS_PRIORITY_CUSTOM = 6

private const val LARGE_SPAN_THRESHOLD = 200f
private const val WEIGHT_SPAN_MEDIUM_THRESHOLD = 100f
private const val MEDIUM_SPAN_THRESHOLD = 20f
private const val SMALL_SPAN_THRESHOLD = 4f
private const val BINARY_SPAN_THRESHOLD = 1.05f
private const val STEP_LARGE = 10f
private const val STEP_INTEGER = 1f
private const val STEP_HALF = 0.5f
private const val STEP_FINE = 0.1f
private const val SNAP_ENDPOINT_FRACTION = 0.25f
private const val TWO_DECIMAL_SCALE = 100f
private const val INTEGER_FORMAT_EPSILON = 0.01f
private const val TENTH_FORMAT_EPSILON = 0.05f
private const val TENTH_FORMAT_SCALE = 10f

internal fun orderVisibleAxes(axes: List<FontVariationAxis>): List<FontVariationAxis> = axes
    .asSequence()
    .filter { !it.hidden && it.minValue < it.maxValue }
    .sortedWith(
        compareBy<FontVariationAxis>(
            { axisDisplayPriority(it.tag) },
            { it.tag.lowercase(Locale.ROOT) },
            { it.tag },
        ),
    ).toList()

internal fun resolveAxisStep(axis: FontVariationAxis): Float {
    val span = (axis.maxValue - axis.minValue).coerceAtLeast(0f)
    if (span == 0f) return STEP_INTEGER
    return when (axis.tag) {
        "wght",
        "GRAD",
        -> when {
            span >= WEIGHT_SPAN_MEDIUM_THRESHOLD -> STEP_LARGE
            span >= MEDIUM_SPAN_THRESHOLD / 2f -> STEP_INTEGER
            else -> STEP_FINE
        }

        "wdth",
        "opsz",
        -> if (span >= MEDIUM_SPAN_THRESHOLD) STEP_INTEGER else STEP_HALF

        "slnt" -> if (span >= SMALL_SPAN_THRESHOLD) STEP_HALF else STEP_FINE

        "ital" -> if (span <= BINARY_SPAN_THRESHOLD) STEP_FINE else STEP_HALF

        else -> when {
            span >= LARGE_SPAN_THRESHOLD -> STEP_LARGE
            span >= MEDIUM_SPAN_THRESHOLD -> STEP_INTEGER
            span >= SMALL_SPAN_THRESHOLD -> STEP_HALF
            else -> STEP_FINE
        }
    }
}

internal fun snapAxisValue(axis: FontVariationAxis, rawValue: Float): Float {
    if (!rawValue.isFinite()) return axis.defaultValue
    val clamped = axis.clamp(rawValue)
    val step = resolveAxisStep(axis)
    if (abs(clamped - axis.minValue) < step * SNAP_ENDPOINT_FRACTION) {
        return roundTwoDecimals(axis.minValue)
    }
    if (abs(axis.maxValue - clamped) < step * SNAP_ENDPOINT_FRACTION) {
        return roundTwoDecimals(axis.maxValue)
    }
    val stepped = (clamped / step).roundToInt() * step
    return roundTwoDecimals(axis.clamp(stepped))
}

internal fun updateAxisOverride(
    axes: List<FontVariationAxis>,
    currentOverrides: Map<String, Float>,
    tag: String,
    rawValue: Float,
): Map<String, Float> {
    val sanitized = CustomFontVariationResolver.sanitizeOverrides(axes, currentOverrides)
    val axis = axes.firstOrNull { it.tag == tag && !it.hidden && it.minValue < it.maxValue } ?: return sanitized
    if (!rawValue.isFinite()) return sanitized
    val snapped = snapAxisValue(axis, rawValue)
    val updated = LinkedHashMap<String, Float>(sanitized)
    if (axis.isDefaultValue(snapped)) {
        updated.remove(tag)
    } else {
        updated[tag] = snapped
    }
    return CustomFontVariationResolver.sanitizeOverrides(axes, updated)
}

internal fun formatAxisValue(value: Float): String {
    if (!value.isFinite()) return "0"
    val roundedInt = value.roundToInt()
    if (abs(value - roundedInt) < INTEGER_FORMAT_EPSILON) {
        return roundedInt.toString()
    }
    val tenths = value * TENTH_FORMAT_SCALE
    return if (abs(tenths - tenths.roundToInt()) < TENTH_FORMAT_EPSILON) {
        String.format(Locale.US, "%.1f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

private fun roundTwoDecimals(value: Float): Float = (value * TWO_DECIMAL_SCALE).roundToInt() / TWO_DECIMAL_SCALE

private fun axisDisplayPriority(tag: String): Int = when (tag) {
    "wght" -> AXIS_PRIORITY_WGHT
    "wdth" -> AXIS_PRIORITY_WDTH
    "opsz" -> AXIS_PRIORITY_OPSZ
    "slnt" -> AXIS_PRIORITY_SLNT
    "ital" -> AXIS_PRIORITY_ITAL
    "GRAD" -> AXIS_PRIORITY_GRAD
    else -> AXIS_PRIORITY_CUSTOM
}
