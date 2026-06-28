package com.lomo.ui.component.stats

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: heatmap month label formatting policy.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: generate compact month labels with separate year metadata for the calendar
 *   heatmap label band.
 *
 * Scenarios:
 * - Given a heatmap range starts mid-year, when month labels are built, then the first month keeps
 *   concise text and exposes the starting year as metadata.
 * - Given a range crosses into January, when labels are built, then January keeps concise text and
 *   exposes the new year as metadata.
 * - Given labels stay within one year, when labels are built, then month text remains short and
 *   year metadata remains available.
 *
 * Observable outcomes:
 * - Generated month label week, text, and year metadata for a given visible week range.
 *
 * TDD proof:
 * - RED: assertions fail before the label presentation update because first and January labels
 *   embed the year in visible text instead of keeping it in MonthLabel.year metadata.
 *
 * Excludes:
 * - Compose canvas rendering, scroll animation timing, popup interaction, and heatmap intensity.
 *
 * Test Change Justification:
 * - Reason category: UI presentation contract correction.
 * - Old behavior/assertion being replaced: first visible month and January assertions expected
 *   visible text such as "2025 May" and "2025 Jan".
 * - Why old assertion is no longer correct: MonthLabel already carries year separately, so the
 *   label band can keep compact month text while preserving year changes in metadata.
 * - Coverage preserved by: the tests still assert the same label positions and the same year
 *   values for mid-year starts, year crossings, and same-year ranges.
 * - Why this is not fitting the test to the implementation: the assertions target the public
 *   MonthLabel output contract consumed by rendering code.
 */
class CalendarHeatmapMonthLabelPolicyTest : UiComponentsFunSpec() {
    init {
        test("first visible month includes year when dataset starts mid year") {
            val labels =
                buildMonthLabels(
                    startDay = LocalDate.of(2025, 5, 5),
                    totalWeeks = 10,
                )

            labels shouldBe
                listOf(
                    MonthLabel(week = 0, text = "May", year = 2025),
                    MonthLabel(week = 4, text = "Jun", year = 2025),
                )
        }

        test("january label includes year when crossing into a new year") {
            val labels =
                buildMonthLabels(
                    startDay = LocalDate.of(2024, 12, 2),
                    totalWeeks = 12,
                )

            labels shouldBe
                listOf(
                    MonthLabel(week = 0, text = "Dec", year = 2024),
                    MonthLabel(week = 5, text = "Jan", year = 2025),
                    MonthLabel(week = 9, text = "Feb", year = 2025),
                )
        }

        test("same year labels stay as short month names") {
            val labels =
                buildMonthLabels(
                    startDay = LocalDate.of(2025, 3, 3),
                    totalWeeks = 10,
                )

            labels shouldBe
                listOf(
                    MonthLabel(week = 0, text = "Mar", year = 2025),
                    MonthLabel(week = 5, text = "Apr", year = 2025),
                )
        }
    }
}
