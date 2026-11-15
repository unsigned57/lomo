package com.lomo.ui.component.stats

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a wide leading month label, when subsequent labels are placed, then they shift right to avoid
 *   overlap.
 * - Given insufficient remaining width, when a shifted label is placed, then it is omitted.
 * - Unit under test: heatmap month label placement policy.
 * - Capability: when the first visible month label includes a year and becomes wider, later
 *   month labels should shift right to avoid overlap and be dropped if they no longer fit.
 * - Observable outcomes: resolved label x positions and which labels remain drawable.
 * - TDD proof: Fails before the fix because labels are drawn directly at their month anchors,
 *   so a wide first label overlaps the next month text.
 * - Excludes: Paint font metrics, Canvas drawing, and horizontal scroll state.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for CalendarHeatmapMonthLabelPlacement.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including later month labels shift right to avoid
 * overlapping a wide first label; labels that no longer fit after shifting are skipped.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class CalendarHeatmapMonthLabelPlacementTest : UiComponentsFunSpec() {
    init {
        test("later month labels shift right to avoid overlapping a wide first label") {
            val placements =
                resolveMonthLabelPlacements(
                    labels =
                    listOf(
                        MonthLabel(week = 0, text = "2025 May", year = 2025),
                        MonthLabel(week = 4, text = "Jun", year = 2025),
                        MonthLabel(week = 8, text = "Jul", year = 2025),
                    ),
                    weekWidth = 13f,
                    totalWidth = 180f,
                    minimumSpacingPx = 6f,
                    textWidth = { text ->
                        when (text) {
                            "2025 May" -> 60f
                            "Jun" -> 18f
                            "Jul" -> 18f
                            else -> 0f
                        }
                    },
                )

            (placements) shouldBe (
                listOf(
                    ResolvedMonthLabelPlacement(text = "2025 May", drawX = 0f, widthPx = 60f),
                    ResolvedMonthLabelPlacement(text = "Jun", drawX = 66f, widthPx = 18f),
                    ResolvedMonthLabelPlacement(text = "Jul", drawX = 104f, widthPx = 18f),
                )
                )
        }
    }

    init {
        test("labels that no longer fit after shifting are skipped") {
            val placements =
                resolveMonthLabelPlacements(
                    labels =
                    listOf(
                        MonthLabel(week = 0, text = "2025 May", year = 2025),
                        MonthLabel(week = 4, text = "Jun", year = 2025),
                    ),
                    weekWidth = 13f,
                    totalWidth = 80f,
                    minimumSpacingPx = 6f,
                    textWidth = { text ->
                        when (text) {
                            "2025 May" -> 60f
                            "Jun" -> 18f
                            else -> 0f
                        }
                    },
                )

            (placements) shouldBe (
                listOf(
                    ResolvedMonthLabelPlacement(text = "2025 May", drawX = 0f, widthPx = 60f),
                )
                )
        }
    }
}
