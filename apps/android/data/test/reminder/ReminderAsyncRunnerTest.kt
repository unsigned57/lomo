package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.reminder.ReminderAsyncRunner
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: reminder BroadcastReceiver async work always finishes PendingResult; failures are
 *   domain results that do not cancel ApplicationScope; cancellation still propagates.
 *
 * Scenarios:
 * - Given receiver async work completes normally, when the runner executes it, then PendingResult
 *   is finished and the result is Completed.
 * - Given receiver async work throws, when the runner executes it, then PendingResult is still
 *   finished, the job completes, and the result is Failed.
 * - Given the launched job is cancelled, when the runner executes it, then PendingResult is
 *   finished and CancellationException propagates.
 *
 * Observable outcomes:
 * - PendingResult.finish() call count, Job completion/cancellation, ReminderReceiverWorkResult.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.ReminderAsyncRunnerTest'
 * - GREEN: finish-once on success/failure/cancel; failure does not cancel the supervisor scope.
 *
 * Excludes:
 * - Android BroadcastReceiver dispatch, DI injection wiring, reminder planning identity (T30/T38).
 *
 * Test Change Justification:
 * - Reason category: security/reliability contract replacement.
 * - Old behavior/assertion being replaced: thrown receiver work cancelled the child job and escaped
 *   to CoroutineExceptionHandler.
 * - Why old assertion is no longer correct: T24 models failure as a domain result so one reminder
 *   receiver error cannot look like an uncaught ApplicationScope crash; goAsync still finishes.
 * - Coverage preserved by: finish() still exactly once; cancellation still propagates.
 * - Why this is not fitting the test to the implementation: the observable is still PendingResult
 *   completion plus whether sibling work would survive (supervisor), not a private handler.
 */

import android.content.BroadcastReceiver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
            var result: ReminderReceiverWorkResult? = null

            val job =
                runner.launch(pendingResult, onResult = { result = it }) {
                    workCompleted = true
                }

            workCompleted shouldBe false
            testScheduler.advanceUntilIdle()

            workCompleted shouldBe true
            job.isCompleted.shouldBeTrue()
            job.isCancelled.shouldBeFalse()
            result shouldBe ReminderReceiverWorkResult.Completed
            verify(exactly = 1) { pendingResult.finish() }
        }
    }

    test("given receiver async work throws when launched then pending result is still finished") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runner = ReminderAsyncRunner(CoroutineScope(SupervisorJob() + dispatcher))
            val pendingResult = pendingResultSpy()
            val failure = IllegalStateException("receiver work failed")
            var result: ReminderReceiverWorkResult? = null

            val job: Job =
                runner.launch(pendingResult, onResult = { result = it }) {
                    throw failure
                }
            testScheduler.advanceUntilIdle()

            job.isCompleted.shouldBeTrue()
            job.isCancelled.shouldBeFalse()
            result.shouldBeInstanceOf<ReminderReceiverWorkResult.Failed>()
            (result as ReminderReceiverWorkResult.Failed).cause shouldBe failure
            verify(exactly = 1) { pendingResult.finish() }
        }
    }

    test("given launched work is cancelled when runner executes then pending result finishes and cancel propagates") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runner = ReminderAsyncRunner(CoroutineScope(SupervisorJob() + dispatcher))
            val pendingResult = pendingResultSpy()
            val started = CompletableDeferred<Unit>()
            var result: ReminderReceiverWorkResult? = null

            val job =
                runner.launch(pendingResult, onResult = { result = it }) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            testScheduler.runCurrent()
            started.await()
            job.cancel(CancellationException("receiver cancelled"))
            testScheduler.advanceUntilIdle()

            job.isCancelled.shouldBeTrue()
            result shouldBe ReminderReceiverWorkResult.Cancelled
            verify(exactly = 1) { pendingResult.finish() }
        }
    }
})

private fun pendingResultSpy(): BroadcastReceiver.PendingResult =
    mockk<BroadcastReceiver.PendingResult>().also { pendingResult ->
        every { pendingResult.finish() } returns Unit
    }
