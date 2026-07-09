package com.lomo.domain.model

class CalendarHeatmapThresholds private constructor(
    val level1Max: Int,
    val level2Max: Int,
    val level3Max: Int,
) {
    val storageValue: String = "$level1Max,$level2Max,$level3Max"

    val ranges: List<CalendarHeatmapThresholdRange> =
        listOf(
            CalendarHeatmapThresholdRange.Empty,
            CalendarHeatmapThresholdRange.Bounded(level = 1, start = MIN_THRESHOLD, endInclusive = level1Max),
            CalendarHeatmapThresholdRange.Bounded(level = 2, start = level1Max + 1, endInclusive = level2Max),
            CalendarHeatmapThresholdRange.Bounded(level = 3, start = level2Max + 1, endInclusive = level3Max),
            CalendarHeatmapThresholdRange.Unbounded(level = 4, start = level3Max + 1),
        )

    fun intensityForCount(count: Int): CalendarHeatmapIntensity =
        when {
            count <= 0 -> CalendarHeatmapIntensity.Empty
            count <= level1Max -> CalendarHeatmapIntensity.Level1
            count <= level2Max -> CalendarHeatmapIntensity.Level2
            count <= level3Max -> CalendarHeatmapIntensity.Level3
            else -> CalendarHeatmapIntensity.Level4
        }

    override fun equals(other: Any?): Boolean =
        other is CalendarHeatmapThresholds &&
            level1Max == other.level1Max &&
            level2Max == other.level2Max &&
            level3Max == other.level3Max

    override fun hashCode(): Int {
        var result = level1Max
        result = 31 * result + level2Max
        result = 31 * result + level3Max
        return result
    }

    override fun toString(): String =
        "CalendarHeatmapThresholds(level1Max=$level1Max, level2Max=$level2Max, level3Max=$level3Max)"

    companion object {
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 99

        fun default(): CalendarHeatmapThresholds =
            of(
                level1Max = 1,
                level2Max = 3,
                level3Max = 6,
            )

        fun of(
            level1Max: Int,
            level2Max: Int,
            level3Max: Int,
        ): CalendarHeatmapThresholds {
            require(level1Max in MIN_THRESHOLD..MAX_THRESHOLD) {
                "Calendar heatmap level 1 maximum must be in $MIN_THRESHOLD..$MAX_THRESHOLD"
            }
            require(level2Max in MIN_THRESHOLD..MAX_THRESHOLD) {
                "Calendar heatmap level 2 maximum must be in $MIN_THRESHOLD..$MAX_THRESHOLD"
            }
            require(level3Max in MIN_THRESHOLD..MAX_THRESHOLD) {
                "Calendar heatmap level 3 maximum must be in $MIN_THRESHOLD..$MAX_THRESHOLD"
            }
            require(level1Max < level2Max && level2Max < level3Max) {
                "Calendar heatmap thresholds must be strictly increasing"
            }
            return CalendarHeatmapThresholds(level1Max, level2Max, level3Max)
        }

        fun parseStorageValue(value: String): CalendarHeatmapThresholds {
            val parts = value.split(",")
            require(parts.size == STORAGE_PART_COUNT) {
                "Calendar heatmap thresholds must contain $STORAGE_PART_COUNT comma-separated integers"
            }
            val boundaries =
                parts.map { part ->
                    requireNotNull(part.trim().toIntOrNull()) {
                        "Calendar heatmap thresholds must contain integers"
                    }
                }
            return of(
                level1Max = boundaries[0],
                level2Max = boundaries[1],
                level3Max = boundaries[2],
            )
        }

        fun parseStorageValueOrNull(value: String): CalendarHeatmapThresholds? =
            // behavior-contract: silent-result-ok: nullable support probe; authoritative parse throws.
            runCatching { parseStorageValue(value) }.getOrNull()

        private const val STORAGE_PART_COUNT = 3
    }
}

sealed interface CalendarHeatmapThresholdRange {
    data object Empty : CalendarHeatmapThresholdRange

    data class Bounded(
        val level: Int,
        val start: Int,
        val endInclusive: Int,
    ) : CalendarHeatmapThresholdRange

    data class Unbounded(
        val level: Int,
        val start: Int,
    ) : CalendarHeatmapThresholdRange
}

enum class CalendarHeatmapIntensity {
    Empty,
    Level1,
    Level2,
    Level3,
    Level4,
}
