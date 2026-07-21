package com.lomo.app.testing

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Installs a test [Dispatchers.Main] for each test case.
 *
 * After each test, Main is left on a safe unconfined fallback instead of [Dispatchers.resetMain].
 * Host JVM unit tests have no Android main looper; resetting to the platform Main lets leaked
 * callbacks from prior specs crash later suites with `UncaughtExceptionsBeforeTest` / missing
 * Main dispatcher failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : BeforeEachListener, AfterEachListener {
    override suspend fun beforeEach(testCase: TestCase) {
        Dispatchers.setMain(testDispatcher)
    }

    override suspend fun afterEach(
        testCase: TestCase,
        result: TestResult,
    ) {
        // Drain any virtual-time work that still owns this test dispatcher before swapping Main.
        testDispatcher.scheduler.advanceUntilIdle()
        // Do not resetMain() to the missing Android platform dispatcher on host JVM tests.
        // Leave a process-wide safe Main so any late/leaked resume cannot poison the next suite.
        Dispatchers.setMain(SAFE_FALLBACK_MAIN)
    }

    companion object {
        private val SAFE_FALLBACK_MAIN = UnconfinedTestDispatcher(name = "safe-main-fallback")

        /**
         * Restores platform Main when a suite truly needs teardown (rare; host unit tests prefer
         * [SAFE_FALLBACK_MAIN]).
         */
        fun resetPlatformMain() {
            Dispatchers.resetMain()
        }
    }
}
