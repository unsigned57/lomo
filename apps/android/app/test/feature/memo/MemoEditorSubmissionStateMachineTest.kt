package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoEditorSubmissionStateMachine.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: convert recoverable submission exceptions into acknowledged failure without
 *   disguising fatal JVM errors as retryable product state.
 *
 * Scenarios:
 * - Given a submission throws an Error, when its coroutine runs, then the Error reaches the
 *   coroutine failure boundary and is not published as MemoEditorSubmissionState.Failed.
 *
 * Observable outcomes:
 * - Coroutine failure, failure callback value, and submission state.
 *
 * TDD proof:
 * - RED on 2026-08-09 because the state machine caught Throwable and converted AssertionError into
 *   a normal Failed state.
 *
 * Excludes:
 * - Repository persistence, Compose rendering, and recoverable Exception message mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorSubmissionStateMachineTest : AppFunSpec() {
    init {
        test("fatal JVM errors cross the coroutine boundary instead of becoming retryable failures") {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher) {
                val uncaughtFailure = CompletableDeferred<Throwable>()
                val exceptionHandler =
                    CoroutineExceptionHandler { _, failure ->
                        uncaughtFailure.complete(failure)
                    }
                val submissionScope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)
                val stateMachine = MemoEditorSubmissionStateMachine()
                val submissionId = MemoEditorSubmissionId(1L)
                var reportedFailure: Throwable? = null

                stateMachine.launch(
                    scope = submissionScope,
                    submissionId = submissionId,
                    onFailure = { failure -> reportedFailure = failure },
                ) {
                    throw AssertionError("fatal")
                }
                testScheduler.runCurrent()

                uncaughtFailure.isCompleted shouldBe true
                uncaughtFailure.await().shouldBeInstanceOf<AssertionError>()
                reportedFailure shouldBe null
                stateMachine.state.value shouldBe MemoEditorSubmissionState.Submitting(submissionId)

                submissionScope.cancel()
            }
        }

        test("reject immediately transitions state machine and resolves awaiting caller to false") {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher) {
                val stateMachine = MemoEditorSubmissionStateMachine()
                val submissionId = MemoEditorSubmissionId(42L)
                val testError = IllegalStateException("Creation rejected")
                var reportedFailure: Exception? = null

                val awaitResult = CompletableDeferred<Boolean>()
                val job = launch {
                    awaitResult.complete(stateMachine.await(submissionId))
                }

                stateMachine.reject(
                    submissionId = submissionId,
                    failure = testError,
                    onFailure = { reportedFailure = it },
                )
                testScheduler.runCurrent()

                awaitResult.isCompleted shouldBe true
                awaitResult.await() shouldBe false
                reportedFailure shouldBe testError
                stateMachine.state.value shouldBe MemoEditorSubmissionState.Failed(submissionId)

                job.cancel()
            }
        }

        test("successful launch resolves awaiting caller to true") {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher) {
                val stateMachine = MemoEditorSubmissionStateMachine()
                val submissionId = MemoEditorSubmissionId(42L)

                val awaitResult = CompletableDeferred<Boolean>()
                val job = launch {
                    awaitResult.complete(stateMachine.await(submissionId))
                }

                stateMachine.launch(
                    scope = this,
                    submissionId = submissionId,
                    onFailure = {},
                ) {
                    // Success block
                }
                testScheduler.runCurrent()

                awaitResult.isCompleted shouldBe true
                awaitResult.await() shouldBe true
                stateMachine.state.value shouldBe MemoEditorSubmissionState.Committed(submissionId)

                job.cancel()
            }
        }
    }
}
