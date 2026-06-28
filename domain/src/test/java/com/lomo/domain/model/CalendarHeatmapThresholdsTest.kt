package com.lomo.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: CalendarHeatmapThresholds.
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: own the daily calendar heatmap intensity threshold contract as a typed
 *   app preference instead of UI-local constants.
 *
 * Scenarios:
 * - Given default thresholds, when storage and range labels are requested, then they match the
 *   previous 1/3/6 heatmap behavior.
 * - Given valid storage text, when parsed, then the exact boundaries are recovered.
 * - Given invalid, non-increasing, or out-of-bound storage text, when parsed, then the value is
 *   rejected instead of silently falling back.
 * - Given memo counts, when intensity is resolved, then zero is empty and positive counts map
 *   through the configured boundaries.
 *
 * Observable outcomes:
 * - Storage value, parsed model equality, thrown IllegalArgumentException, range labels, and
 *   HeatmapIntensity values.
 *
 * TDD proof:
 * - RED: CalendarHeatmapThresholds does not exist and heatmap intensity is hard-coded in the UI
 *   component before the typed preference contract is introduced.
 *
 * Excludes:
 * - Compose rendering, DataStore implementation, and settings dialog text input mechanics.
 */
class CalendarHeatmapThresholdsTest : FunSpec({
    test("given defaults when inspected then previous calendar heatmap ranges are preserved") {
        val thresholds = CalendarHeatmapThresholds.default()

        thresholds.storageValue shouldBe "1,3,6"
        thresholds.ranges shouldBe
            listOf(
                CalendarHeatmapThresholdRange.Empty,
                CalendarHeatmapThresholdRange.Bounded(level = 1, start = 1, endInclusive = 1),
                CalendarHeatmapThresholdRange.Bounded(level = 2, start = 2, endInclusive = 3),
                CalendarHeatmapThresholdRange.Bounded(level = 3, start = 4, endInclusive = 6),
                CalendarHeatmapThresholdRange.Unbounded(level = 4, start = 7),
            )
    }

    test("given valid storage text when parsed then exact boundaries are recovered") {
        CalendarHeatmapThresholds.parseStorageValue("2,5,9") shouldBe
            CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)
    }

    test("given invalid storage text when parsed then it is rejected") {
        listOf(
            "",
            "1,3",
            "1,3,6,9",
            "one,3,6",
            "0,3,6",
            "1,1,6",
            "1,6,3",
            "1,3,100",
        ).forEach { raw ->
            shouldThrow<IllegalArgumentException> {
                CalendarHeatmapThresholds.parseStorageValue(raw)
            }
        }
    }

    test("given configured thresholds when count intensity is resolved then boundaries drive every level") {
        val thresholds = CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)

        thresholds.intensityForCount(0) shouldBe CalendarHeatmapIntensity.Empty
        thresholds.intensityForCount(1) shouldBe CalendarHeatmapIntensity.Level1
        thresholds.intensityForCount(2) shouldBe CalendarHeatmapIntensity.Level1
        thresholds.intensityForCount(3) shouldBe CalendarHeatmapIntensity.Level2
        thresholds.intensityForCount(5) shouldBe CalendarHeatmapIntensity.Level2
        thresholds.intensityForCount(6) shouldBe CalendarHeatmapIntensity.Level3
        thresholds.intensityForCount(9) shouldBe CalendarHeatmapIntensity.Level3
        thresholds.intensityForCount(10) shouldBe CalendarHeatmapIntensity.Level4
    }
})
