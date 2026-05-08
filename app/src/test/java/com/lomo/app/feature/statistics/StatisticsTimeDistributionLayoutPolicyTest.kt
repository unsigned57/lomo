package com.lomo.app.feature.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Statistics time-distribution layout policy.
 * - Behavior focus: the bottom time-distribution charts must stay stacked on phone-width screens
 *   but use a balanced two-column layout once the available width can support tablet chart panes.
 * - Observable outcomes: resolved layout mode for representative compact, boundary, and tablet
 *   widths.
 * - Red phase: Fails before the fix because StatisticsScreen has no extracted width policy and
 *   always stacks the weekly heatmap and hourly chart.
 * - Excludes: Compose measurement, chart Canvas drawing, and runtime window-size-class plumbing.
 */
class StatisticsTimeDistributionLayoutPolicyTest {
    @Test
    fun `phone and narrow tablet widths keep time charts stacked`() {
        assertEquals(
            StatisticsTimeDistributionLayout.Stacked,
            resolveStatisticsTimeDistributionLayout(widthDp = 360),
        )
        assertEquals(
            StatisticsTimeDistributionLayout.Stacked,
            resolveStatisticsTimeDistributionLayout(widthDp = 719),
        )
    }

    @Test
    fun `tablet widths use balanced two column time charts`() {
        assertEquals(
            StatisticsTimeDistributionLayout.TwoColumn,
            resolveStatisticsTimeDistributionLayout(widthDp = 720),
        )
        assertEquals(
            StatisticsTimeDistributionLayout.TwoColumn,
            resolveStatisticsTimeDistributionLayout(widthDp = 840),
        )
    }
}
