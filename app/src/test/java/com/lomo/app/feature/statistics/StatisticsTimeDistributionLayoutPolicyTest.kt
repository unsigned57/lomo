/*
 * Behavior Contract:
 * - Unit under test: Statistics time-distribution layout policy.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: determine statistics time distribution layout mode based on window width.
 *
 * Scenarios:
 * - Given width is below 720dp, when resolving layout, then return StatisticsTimeDistributionLayout.Stacked.
 * - Given width is at or above 720dp, when resolving layout, then return StatisticsTimeDistributionLayout.TwoColumn.
 *
 * Observable outcomes:
 * - StatisticsTimeDistributionLayout enum value.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Compose measurement, chart Canvas drawing, and runtime window-size-class plumbing.
 */

package com.lomo.app.feature.statistics

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class StatisticsTimeDistributionLayoutPolicyTest : AppFunSpec() {
    init {
        test("phone and narrow tablet widths keep time charts stacked") {
            (resolveStatisticsTimeDistributionLayout(widthDp = 360)) shouldBe (StatisticsTimeDistributionLayout.Stacked)
            (resolveStatisticsTimeDistributionLayout(widthDp = 719)) shouldBe (StatisticsTimeDistributionLayout.Stacked)
        }

        test("tablet widths use balanced two column time charts") {
            (resolveStatisticsTimeDistributionLayout(widthDp = 720)) shouldBe (StatisticsTimeDistributionLayout.TwoColumn)
            (resolveStatisticsTimeDistributionLayout(widthDp = 840)) shouldBe (StatisticsTimeDistributionLayout.TwoColumn)
        }
    }
}
