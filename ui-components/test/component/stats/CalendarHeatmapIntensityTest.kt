package com.lomo.ui.component.stats

import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: calendar heatmap intensity resolution.
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: render daily calendar heatmap colors from the caller-provided threshold policy
 *   rather than hidden component constants.
 *
 * Scenarios:
 * - Given default thresholds, when counts are resolved, then the legacy 1/3/6 calendar heatmap
 *   buckets remain intact.
 * - Given custom thresholds, when counts are resolved, then color levels follow the custom
 *   boundaries.
 *
 * Observable outcomes:
 * - HeatmapIntensity values returned for representative counts.
 *
 * TDD proof:
 * - RED: resolveHeatmapIntensity currently accepts only count and uses hard-coded constants.
 *
 * Excludes:
 * - Canvas drawing, Material colors, DataStore persistence, and settings screen controls.
 */
class CalendarHeatmapIntensityTest : UiComponentsFunSpec() {
    init {
        test("given default thresholds when resolving intensity then legacy buckets are preserved") {
            val thresholds = CalendarHeatmapThresholds.default()

            resolveHeatmapIntensity(0, thresholds) shouldBe HeatmapIntensity.Empty
            resolveHeatmapIntensity(1, thresholds) shouldBe HeatmapIntensity.Level1
            resolveHeatmapIntensity(2, thresholds) shouldBe HeatmapIntensity.Level2
            resolveHeatmapIntensity(3, thresholds) shouldBe HeatmapIntensity.Level2
            resolveHeatmapIntensity(4, thresholds) shouldBe HeatmapIntensity.Level3
            resolveHeatmapIntensity(6, thresholds) shouldBe HeatmapIntensity.Level3
            resolveHeatmapIntensity(7, thresholds) shouldBe HeatmapIntensity.Level4
        }

        test("given custom thresholds when resolving intensity then caller policy is used") {
            val thresholds = CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)

            resolveHeatmapIntensity(2, thresholds) shouldBe HeatmapIntensity.Level1
            resolveHeatmapIntensity(3, thresholds) shouldBe HeatmapIntensity.Level2
            resolveHeatmapIntensity(5, thresholds) shouldBe HeatmapIntensity.Level2
            resolveHeatmapIntensity(6, thresholds) shouldBe HeatmapIntensity.Level3
            resolveHeatmapIntensity(9, thresholds) shouldBe HeatmapIntensity.Level3
            resolveHeatmapIntensity(10, thresholds) shouldBe HeatmapIntensity.Level4
        }
    }
}
