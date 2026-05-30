/*
 * Behavior Contract:
 * - Unit under test: stats chart presentation policies.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: normalize reusable statistics chart input without depending on domain models or hidden clocks.
 *
 * Scenarios:
 * - Given tag chart presentation slices are unsorted and exceed the visible limit, when resolving
 *   bars, then only the top tags are shown with deterministic intensity fractions.
 * - Given a caller-supplied today and memo dates before and after today, when resolving the
 *   calendar heatmap window, then the visible range is anchored to today and future dates do not
 *   extend or populate the window.
 * - Given stale or future memo data, when calling CalendarHeatmap, then the caller must supply
 *   today explicitly so data keys cannot become the implicit window anchor.
 * - Given memo counts cross heatmap thresholds, when resolving intensity, then each count maps to
 *   the explicit display level used by the chart.
 *
 * Observable outcomes:
 * - Resolved tag bar order, bar fraction, visible heatmap window, included heatmap cells, and
 *   heatmap intensity level, plus the CalendarHeatmap API requirement that today is explicit.
 *
 * TDD proof:
 * - RED: the first run fails to compile because ChartTagSlice, resolveTagDistributionBars,
 *   resolveCalendarHeatmapWindow, and resolveHeatmapIntensity do not exist yet.
 * - RED: CalendarHeatmap today parameter is optional, allowing legacy calls to derive today from
 *   memoCountByDate keys.
 *
 * Excludes:
 * - Compose rendering, Android resources, popup animation, and domain statistics calculation.
 */

package com.lomo.ui.component.stats

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class StatsChartPolicyTest : UiComponentsFunSpec() {
    init {
        test("given many tag slices when resolving bars then top ten are ordered with fractions") {
            val slices =
                (1..12)
                    .map { index -> ChartTagSlice(name = "tag-$index", count = index) }
                    .shuffled()

            val bars = resolveTagDistributionBars(slices)

            bars.map { it.slice.name } shouldContainExactly
                listOf("tag-12", "tag-11", "tag-10", "tag-9", "tag-8", "tag-7", "tag-6", "tag-5", "tag-4", "tag-3")
            bars.first().fraction shouldBe 1f
            bars.last().fraction shouldBe 0.25f
        }

        test("given explicit today when resolving heatmap window then future dates are excluded") {
            val today = LocalDate.of(2026, 5, 22)
            val futureDate = today.plusDays(1)
            val memoCountByDate =
                mapOf(
                    today.minusDays(370) to 2,
                    today.minusDays(1) to 3,
                    today to 4,
                    futureDate to 9,
                )

            val window =
                resolveCalendarHeatmapWindow(
                    today = today,
                    memoCountByDate = memoCountByDate,
                )

            window.today shouldBe today
            window.heatmapCells.any { it.date == futureDate } shouldBe false
            window.heatmapCells.first().date.isAfter(today) shouldBe false
            window.heatmapCells.last().date shouldBe today
            window.heatmapCells.associate { it.date to it.count }[today] shouldBe 4
        }

        test("given future data maximum when resolving heatmap window then caller today remains the anchor") {
            val today = LocalDate.of(2026, 5, 22)
            val futureMaximumDate = today.plusMonths(3)
            val memoCountByDate =
                mapOf(
                    today.minusDays(30) to 2,
                    futureMaximumDate to 9,
                )

            val window =
                resolveCalendarHeatmapWindow(
                    today = today,
                    memoCountByDate = memoCountByDate,
                )

            window.today shouldBe today
            window.heatmapCells.last().date shouldBe today
            window.heatmapCells.any { it.date == futureMaximumDate } shouldBe false
        }

        test("heatmap intensity thresholds are explicit display policy") {
            mapOf(
                0 to HeatmapIntensity.Empty,
                1 to HeatmapIntensity.Level1,
                2 to HeatmapIntensity.Level2,
                3 to HeatmapIntensity.Level2,
                4 to HeatmapIntensity.Level3,
                6 to HeatmapIntensity.Level3,
                7 to HeatmapIntensity.Level4,
            ).forEach { (count, expectedIntensity) ->
                withClue("count=$count") {
                    resolveHeatmapIntensity(count) shouldBe expectedIntensity
                }
            }
        }
    }
}
