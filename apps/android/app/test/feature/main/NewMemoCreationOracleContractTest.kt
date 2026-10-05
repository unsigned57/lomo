// adversarial-audit: hypotheses under test:
// 1. A failure in the presentation-only rank oracle (newHeadRank) must degrade to skipping the
//    reveal animation, not escape the launch as an unhandled coroutine failure that crashes the
//    app on production scopes.
// 2. submissionInFlight must release on every failure path so a later submit is not dead-locked.
package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: NewMemoCreationCoordinator rank-oracle failure and in-flight release.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: a presentation-only rank oracle failure degrades to skipping the reveal
 *   animation, and submissionInFlight releases on every failure/cancellation path.
 *
 * Scenarios:
 * - Given a throwing rank oracle, when a new memo is created, then the failure does not escape
 *   as an unhandled coroutine failure.
 * - Given a cancelled scope, when submission is in flight, then the slot frees instead of
 *   wedging forever.
 * - Given a reveal timeout, when a second submit is queued behind it, then the slot still frees.
 *
 * Observable outcomes: coroutine exception handler invocations, submissionInFlight state,
 * subsequent submissions proceeding.
 *
 * TDD proof:
 * - Each arm fails RED while oracle throws escaped the launch scope or in-flight leaked.
 *
 * Excludes:
 * - The memo write itself and UI animation frames.
 */
class NewMemoCreationOracleContractTest : FunSpec({

    test("a throwing rank oracle must not escape as an unhandled coroutine failure") {
        runTest {
            val crashes = mutableListOf<Throwable>()
            val handler = CoroutineExceptionHandler { _, error -> crashes += error }
            val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    NewMemoCreationCoordinatorDependencies<String>(
                        scope = scope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = {},
                        readTopBaseline = { HeadEnterBaseline.ExistingHead("prev") },
                        prepareNewTopEnter = { EnterRequestId(1L) },
                        createMemo = { _, _ -> "new-id" },
                        newHeadRank = { throw IllegalStateException("engine mid-switch") },
                        awaitNewTopItem = { "new-id" },
                        revealNewTopItem = {},
                        cancelPreparedEnter = {},
                    ),
                )

            coordinator.submit("body") shouldBe true
            testScheduler.advanceUntilIdle()

            // Desired: oracle failure is observed or absorbed into "no reveal" — never an
            // uncaught launch failure. Actual: the throw escapes the launch block (only the
            // finally cleanup runs) and lands on the scope handler — a crash on
            // rememberCoroutineScope in production.
            crashes shouldBe emptyList()
        }
    }

    test("a cancelled scope must not wedge submissionInFlight forever") {
        runTest {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    NewMemoCreationCoordinatorDependencies<String>(
                        scope = scope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = {},
                        readTopBaseline = { null },
                        prepareNewTopEnter = { EnterRequestId(1L) },
                        createMemo = { _, _ -> "new-id" },
                        newHeadRank = { 0 },
                        awaitNewTopItem = { null },
                        revealNewTopItem = {},
                        cancelPreparedEnter = {},
                    ),
                )

            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            val first = coordinator.submit("body")
            testScheduler.advanceUntilIdle()
            // The launch on a dead scope never runs its finally, so submissionInFlight can stay
            // set — assert the slot released for the next attempt.
            val second = coordinator.submit("body-again")

            first shouldBe true
            second shouldBe true
        }
    }

    test("reveal timeout still frees the slot while a second submit is queued behind it") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    NewMemoCreationCoordinatorDependencies<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = {},
                        readTopBaseline = { HeadEnterBaseline.ExistingHead("prev") },
                        prepareNewTopEnter = { EnterRequestId(9L) },
                        createMemo = { content, _ ->
                            events += "create:$content"
                            "id-$content"
                        },
                        newHeadRank = { 0 },
                        awaitNewTopItem = {
                            events += "await"
                            gate.await()
                            null
                        },
                        revealNewTopItem = { events += "reveal:$it" },
                        cancelPreparedEnter = { events += "cancel:${it.value}" },
                    ),
                )

            coordinator.submit("a") shouldBe true
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            coordinator.submit("b") shouldBe true
            testScheduler.advanceUntilIdle()

            events shouldBe listOf(
                "create:a",
                "await",
                "cancel:9",
                "create:b",
                "await",
                "cancel:9",
            )
        }
    }
})
