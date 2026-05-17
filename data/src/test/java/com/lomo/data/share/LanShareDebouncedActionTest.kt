/*
 * Test Contract:
 * - Unit under test: LanShareDebouncedActionTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for LanShareDebouncedActionTest.
 * - Boundary: boundary and edge cases for LanShareDebouncedActionTest.
 * - Failure: failure and error scenarios for LanShareDebouncedActionTest.
 * - Must-not-happen: invariants are never violated for LanShareDebouncedActionTest.
 *
 * - Behavior focus: test behavioral outcomes of LanShareDebouncedActionTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.share


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

@OptIn(ExperimentalCoroutinesApi::class)
class LanShareDebouncedActionTest : DataFunSpec() {
    init {
        test("trigger coalesces rapid calls into one action") { `trigger coalesces rapid calls into one action`() }

        test("cancel prevents pending action from running") { `cancel prevents pending action from running`() }
    }


    private fun `trigger coalesces rapid calls into one action`() {
        runTest {
            var executions = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val debouncer =
                LanShareDebouncedAction(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    delayMs = 300L,
                    action = { executions++ },
                )

            debouncer.trigger()
            advanceTimeBy(200L)
            runCurrent()
            debouncer.trigger()
            advanceTimeBy(200L)
            runCurrent()

            executions shouldBe 0

            advanceTimeBy(100L)
            runCurrent()

            executions shouldBe 1
        }
    }

    private fun `cancel prevents pending action from running`() {
        runTest {
            var executions = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val debouncer =
                LanShareDebouncedAction(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    delayMs = 300L,
                    action = { executions++ },
                )

            debouncer.trigger()
            debouncer.cancel()
            advanceTimeBy(300L)
            runCurrent()

            executions shouldBe 0
        }
    }
}
