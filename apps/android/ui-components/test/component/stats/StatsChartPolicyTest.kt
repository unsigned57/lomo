package com.lomo.ui.component.stats

import com.lomo.ui.testing.UiComponentsFunSpec
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
            val futureMaximumDate = today.plusMonths(3)
            val memoCountByDate =
                mapOf(
                    today.minusDays(370) to 2,
                    today.minusDays(1) to 3,
                    today to 4,
                    futureDate to 9,
                    futureMaximumDate to 10,
                )

            val window =
                resolveCalendarHeatmapWindow(
                    today = today,
                    memoCountByDate = memoCountByDate,
                )

            window.today shouldBe today
            window.heatmapCells.any { it.date.isAfter(today) } shouldBe false
            window.heatmapCells.first().date.isAfter(today) shouldBe false
            window.heatmapCells.last().date shouldBe today
            window.heatmapCells.associate { it.date to it.count }[today] shouldBe 4
        }
    }
}
