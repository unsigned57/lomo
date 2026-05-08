package com.lomo.data.share

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanShareDebouncedActionTest {
    @Test
    fun `trigger coalesces rapid calls into one action`() {
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

            assertEquals(0, executions)

            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, executions)
        }
    }

    @Test
    fun `cancel prevents pending action from running`() {
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

            assertEquals(0, executions)
        }
    }
}
