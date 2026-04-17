package com.lomo.ui.component.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: heatmap month label placement policy.
 * - Behavior focus: when the first visible month label includes a year and becomes wider, later
 *   month labels should shift right to avoid overlap and be dropped if they no longer fit.
 * - Observable outcomes: resolved label x positions and which labels remain drawable.
 * - Red phase: Fails before the fix because labels are drawn directly at their month anchors,
 *   so a wide first label overlaps the next month text.
 * - Excludes: Paint font metrics, Canvas drawing, and horizontal scroll state.
 */
class CalendarHeatmapMonthLabelPlacementTest {
    @Test
    fun `later month labels shift right to avoid overlapping a wide first label`() {
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

        assertEquals(
            listOf(
                ResolvedMonthLabelPlacement(text = "2025 May", drawX = 0f, widthPx = 60f),
                ResolvedMonthLabelPlacement(text = "Jun", drawX = 66f, widthPx = 18f),
                ResolvedMonthLabelPlacement(text = "Jul", drawX = 104f, widthPx = 18f),
            ),
            placements,
        )
    }

    @Test
    fun `labels that no longer fit after shifting are skipped`() {
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

        assertEquals(
            listOf(
                ResolvedMonthLabelPlacement(text = "2025 May", drawX = 0f, widthPx = 60f),
            ),
            placements,
        )
    }
}
