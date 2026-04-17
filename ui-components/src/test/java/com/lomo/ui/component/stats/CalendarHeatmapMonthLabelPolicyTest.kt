package com.lomo.ui.component.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: heatmap month label formatting policy.
 * - Behavior focus: the existing top label band should keep ordinary month labels concise within
 *   a year, but include the year for the first visible month of the dataset and for January when
 *   crossing into a new year, so year changes remain visible during horizontal scrolling.
 * - Observable outcomes: generated month label text and year metadata for a given week range.
 * - Red phase: Fails before the fix because a heatmap that starts in May 2025 emits only "May",
 *   hiding the starting year until 2026 appears later in the range.
 * - Excludes: Compose canvas rendering, scroll animation timing, and popup interaction.
 */
class CalendarHeatmapMonthLabelPolicyTest {
    @Test
    fun `first visible month includes year when dataset starts mid year`() {
        val labels =
            buildMonthLabels(
                startDay = LocalDate.of(2025, 5, 5),
                totalWeeks = 10,
            )

        assertEquals(
            listOf(
                MonthLabel(week = 0, text = "2025 May", year = 2025),
                MonthLabel(week = 4, text = "Jun", year = 2025),
            ),
            labels,
        )
    }

    @Test
    fun `january label includes year when crossing into a new year`() {
        val labels =
            buildMonthLabels(
                startDay = LocalDate.of(2024, 12, 2),
                totalWeeks = 12,
            )

        assertEquals(
            listOf(
                MonthLabel(week = 0, text = "2024 Dec", year = 2024),
                MonthLabel(week = 5, text = "2025 Jan", year = 2025),
                MonthLabel(week = 9, text = "Feb", year = 2025),
            ),
            labels,
        )
    }

    @Test
    fun `same year labels stay as short month names`() {
        val labels =
            buildMonthLabels(
                startDay = LocalDate.of(2025, 3, 3),
                totalWeeks = 10,
            )

        assertEquals(
            listOf(
                MonthLabel(week = 0, text = "2025 Mar", year = 2025),
                MonthLabel(week = 5, text = "Apr", year = 2025),
            ),
            labels,
        )
    }
}
