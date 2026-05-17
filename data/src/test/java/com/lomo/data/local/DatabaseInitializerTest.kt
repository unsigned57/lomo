package com.lomo.data.local


import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: DatabaseInitializer
 *
 * Scenario matrix:
 * - Happy: standard happy path for DatabaseInitializerTest.
 * - Boundary: boundary and edge cases for DatabaseInitializerTest.
 * - Failure: failure and error scenarios for DatabaseInitializerTest.
 * - Must-not-happen: invariants are never violated for DatabaseInitializerTest.
 * - Behavior focus: async Room readiness probing should coalesce concurrent callers, publish readiness,
 *   and preserve the existing "database preserved" failure contract when open or migration fails.
 * - Observable outcomes: open attempt count, readyFlow state transitions, and wrapped failure message.
 * - Red phase: Fails before the fix because DatabaseInitializer does not exist and the startup open
 *   probe still lives inside DataModule.
 * - Excludes: DAO query behavior, actual Android Room migrations, and Hilt wiring.
 */
class DatabaseInitializerTest : DataFunSpec() {
    init {
        test("ensureOpen coalesces concurrent callers and marks ready after success") { `ensureOpen coalesces concurrent callers and marks ready after success`() }

        test("ensureOpen publishes wrapped failure and rethrows the cached exception") { `ensureOpen publishes wrapped failure and rethrows the cached exception`() }
    }




    private fun `ensureOpen coalesces concurrent callers and marks ready after success`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var openCalls = 0
            val initializer =
                DatabaseInitializer(
                    databasePath = "/data/user/0/com.lomo.app/databases/lomo.db",
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    performOpen = {
                        openCalls += 1
                        gate.await()
                    },
                )

            val first = async { initializer.ensureOpen() }
            val second = async { initializer.ensureOpen() }
            kotlinx.coroutines.yield()

            initializer.readyFlow.value shouldBe DatabaseInitializer.DbReadiness.Opening
            gate.complete(Unit)
            first.await()
            second.await()

            openCalls shouldBe 1
            initializer.readyFlow.value shouldBe DatabaseInitializer.DbReadiness.Ready
        }

    private fun `ensureOpen publishes wrapped failure and rethrows the cached exception`() =
        runTest {
            val cause = IllegalArgumentException("migration boom")
            val initializer =
                DatabaseInitializer(
                    databasePath = "/data/user/0/com.lomo.app/databases/lomo.db",
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    performOpen = {
                        throw cause
                    },
                )

            val firstError: IllegalStateException =
                try {
                    initializer.ensureOpen()
                    throw AssertionError("ensureOpen should rethrow a wrapped IllegalStateException")
                } catch (error: IllegalStateException) {
                    error
                }
            val failureState = initializer.readyFlow.value as DatabaseInitializer.DbReadiness.Failure
            val secondError: IllegalStateException =
                try {
                    initializer.ensureOpen()
                    throw AssertionError("ensureOpen should reuse the cached IllegalStateException")
                } catch (error: IllegalStateException) {
                    error
                }

            firstError.message shouldBe "Database open/migration failed; existing database preserved at /data/user/0/com.lomo.app/databases/lomo.db"
            (failureState.error === firstError).shouldBeTrue()
            (secondError === firstError).shouldBeTrue()
            firstError.cause!!::class.java shouldBe IllegalArgumentException::class.java
            firstError.cause!!.message shouldBe "migration boom"
        }
}
