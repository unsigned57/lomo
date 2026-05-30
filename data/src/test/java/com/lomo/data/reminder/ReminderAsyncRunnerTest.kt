package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.reminder.ReminderAsyncRunner
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: reminder BroadcastReceiver async work completes its PendingResult exactly once
 *   through a shared, injectable runner.
 *
 * Scenarios:
 * - Given receiver async work completes normally, when the runner executes it, then PendingResult is finished.
 * - Given receiver async work throws, when the runner executes it, then PendingResult is still finished.
 * - Given tests provide a controlled coroutine scope, when work is launched, then the test scheduler controls completion.
 * - Given receiver code must not own anonymous coroutine scopes, when tests run, then finish behavior is locked in the runner.
 *
 * Observable outcomes:
 * - PendingResult.finish() call count and coroutine Job completion state.
 *
 * TDD proof:
 * - Target command: ./gradlew --no-daemon --no-configuration-cache --console=plain
 *   :data:testDebugUnitTest --tests 'com.lomo.data.reminder.ReminderAsyncRunnerTest'
 * - Observed RED: test compilation failed with unresolved reference errors for ReminderAsyncRunner when the normal
 *   completion and throwing-work scenarios tried to construct the shared runner seam.
 * - Why RED proves the behavior was missing: the receiver-owned anonymous coroutine launch could not be injected
 *   with a test scheduler or called directly by the tests, so there was no observable contract guaranteeing that
 *   PendingResult.finish() ran exactly once from a finally path after both successful and failing receiver work.
 *
 * Excludes:
 * - Android BroadcastReceiver dispatch, Hilt injection wiring, reminder business decisions, and manifest registration.
 */

import android.content.BroadcastReceiver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class ReminderAsyncRunnerTest : FunSpec({
    test("given receiver async work completes normally when launched then pending result is finished") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runner = ReminderAsyncRunner(CoroutineScope(SupervisorJob() + dispatcher))
            val pendingResult = pendingResultSpy()
            var workCompleted = false

            val job = runner.launch(pendingResult) {
                workCompleted = true
            }

            workCompleted shouldBe false
            testScheduler.advanceUntilIdle()

            workCompleted shouldBe true
            job.isCompleted.shouldBeTrue()
            job.isCancelled.shouldBeFalse()
            verify(exactly = 1) { pendingResult.finish() }
        }
    }

    test("given receiver async work throws when launched then pending result is still finished") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var observedFailure: Throwable? = null
            val exceptionHandler =
                CoroutineExceptionHandler { _, error ->
                    observedFailure = error
                }
            val runner = ReminderAsyncRunner(CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler))
            val pendingResult = pendingResultSpy()
            val failure = IllegalStateException("receiver work failed")

            val job: Job =
                runner.launch(pendingResult) {
                    throw failure
                }
            testScheduler.advanceUntilIdle()

            job.isCancelled.shouldBeTrue()
            observedFailure shouldBe failure
            verify(exactly = 1) { pendingResult.finish() }
        }
    }
})

private fun pendingResultSpy(): BroadcastReceiver.PendingResult =
    mockk<BroadcastReceiver.PendingResult>().also { pendingResult ->
        every { pendingResult.finish() } returns Unit
    }
