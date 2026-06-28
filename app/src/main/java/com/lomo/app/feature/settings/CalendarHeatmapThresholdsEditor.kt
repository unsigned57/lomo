package com.lomo.app.feature.settings

import com.lomo.domain.model.CalendarHeatmapThresholdRange
import com.lomo.domain.model.CalendarHeatmapThresholds

data class CalendarHeatmapThresholdEditorInput(
    val level1Max: String,
    val level2Max: String,
    val level3Max: String,
)

data class CalendarHeatmapThresholdEditorState(
    val input: CalendarHeatmapThresholdEditorInput,
    val thresholds: CalendarHeatmapThresholds?,
    val validationError: CalendarHeatmapThresholdValidationError?,
) {
    val canSave: Boolean
        get() = thresholds != null
}

enum class CalendarHeatmapThresholdValidationError {
    NON_NUMERIC,
    OUT_OF_RANGE,
    NOT_STRICTLY_INCREASING,
}

data class CalendarHeatmapThresholdRangeRow(
    val range: CalendarHeatmapThresholdCountRange,
    val tone: CalendarHeatmapThresholdTone,
)

sealed interface CalendarHeatmapThresholdCountRange {
    data object Empty : CalendarHeatmapThresholdCountRange

    data class Single(
        val count: Int,
    ) : CalendarHeatmapThresholdCountRange

    data class Bounded(
        val start: Int,
        val endInclusive: Int,
    ) : CalendarHeatmapThresholdCountRange

    data class Unbounded(
        val start: Int,
    ) : CalendarHeatmapThresholdCountRange
}

enum class CalendarHeatmapThresholdTone {
    Blank,
    Light,
    Medium,
    Darker,
    Darkest,
}

fun CalendarHeatmapThresholds.toCalendarHeatmapThresholdEditorInput(): CalendarHeatmapThresholdEditorInput =
    CalendarHeatmapThresholdEditorInput(
        level1Max = level1Max.toString(),
        level2Max = level2Max.toString(),
        level3Max = level3Max.toString(),
    )

fun resolveCalendarHeatmapThresholdEditorState(
    input: CalendarHeatmapThresholdEditorInput,
): CalendarHeatmapThresholdEditorState {
    val values =
        listOf(input.level1Max, input.level2Max, input.level3Max)
            .map { value -> value.trim().toIntOrNull() }
    if (values.any { value -> value == null }) {
        return input.invalid(CalendarHeatmapThresholdValidationError.NON_NUMERIC)
    }
    val level1Max = values[0] ?: error("Calendar heatmap threshold parser lost level 1")
    val level2Max = values[1] ?: error("Calendar heatmap threshold parser lost level 2")
    val level3Max = values[2] ?: error("Calendar heatmap threshold parser lost level 3")
    val allowedRange = CalendarHeatmapThresholds.MIN_THRESHOLD..CalendarHeatmapThresholds.MAX_THRESHOLD
    if (level1Max !in allowedRange || level2Max !in allowedRange || level3Max !in allowedRange) {
        return input.invalid(CalendarHeatmapThresholdValidationError.OUT_OF_RANGE)
    }
    if (!(level1Max < level2Max && level2Max < level3Max)) {
        return input.invalid(CalendarHeatmapThresholdValidationError.NOT_STRICTLY_INCREASING)
    }
    return CalendarHeatmapThresholdEditorState(
        input = input,
        thresholds =
            CalendarHeatmapThresholds.of(
                level1Max = level1Max,
                level2Max = level2Max,
                level3Max = level3Max,
            ),
        validationError = null,
    )
}

fun calendarHeatmapThresholdRangeRows(
    thresholds: CalendarHeatmapThresholds,
): List<CalendarHeatmapThresholdRangeRow> =
    thresholds.ranges.map { range ->
        when (range) {
            CalendarHeatmapThresholdRange.Empty ->
                CalendarHeatmapThresholdRangeRow(
                    range = CalendarHeatmapThresholdCountRange.Empty,
                    tone = CalendarHeatmapThresholdTone.Blank,
                )
            is CalendarHeatmapThresholdRange.Bounded ->
                CalendarHeatmapThresholdRangeRow(
                    range =
                        if (range.start == range.endInclusive) {
                            CalendarHeatmapThresholdCountRange.Single(range.start)
                        } else {
                            CalendarHeatmapThresholdCountRange.Bounded(
                                start = range.start,
                                endInclusive = range.endInclusive,
                            )
                        },
                    tone = range.level.toCalendarHeatmapThresholdTone(),
                )
            is CalendarHeatmapThresholdRange.Unbounded ->
                CalendarHeatmapThresholdRangeRow(
                    range = CalendarHeatmapThresholdCountRange.Unbounded(range.start),
                    tone = range.level.toCalendarHeatmapThresholdTone(),
                )
        }
    }

private fun CalendarHeatmapThresholdEditorInput.invalid(
    error: CalendarHeatmapThresholdValidationError,
): CalendarHeatmapThresholdEditorState =
    CalendarHeatmapThresholdEditorState(
        input = this,
        thresholds = null,
        validationError = error,
    )

private fun Int.toCalendarHeatmapThresholdTone(): CalendarHeatmapThresholdTone =
    when (this) {
        HEATMAP_LEVEL_LIGHT -> CalendarHeatmapThresholdTone.Light
        HEATMAP_LEVEL_MEDIUM -> CalendarHeatmapThresholdTone.Medium
        HEATMAP_LEVEL_DARKER -> CalendarHeatmapThresholdTone.Darker
        HEATMAP_LEVEL_DARKEST -> CalendarHeatmapThresholdTone.Darkest
        else -> error("Unknown calendar heatmap threshold level: $this")
    }

private const val HEATMAP_LEVEL_LIGHT = 1
private const val HEATMAP_LEVEL_MEDIUM = 2
private const val HEATMAP_LEVEL_DARKER = 3
private const val HEATMAP_LEVEL_DARKEST = 4
