// adversarial-audit: hypotheses under test:
// 1. A failure in the presentation-only rank oracle (createdMemoRank) must degrade to skipping the
//    reveal animation, not escape the launch as an unhandled coroutine failure that crashes the
//    app on production scopes.
// 2. submissionInFlight must release on every failure path so a later submit is not dead-locked.
package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterRequestId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
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
 * Production scope: apps/android/app/src/feature/main/NewMemoCreationCoordinator.kt
 * Test Change Justification:
 * Reason category: mechanical interface migration.
 * Old behavior/assertion being replaced: baseline and deferred reveal callback signatures.
 * Why old assertion is no longer correct: the API now binds committed identity and acknowledges layout.
 * Coverage preserved by: all existing failure, cancellation, timeout and retry assertions remain.
 * Why this is not fitting the test to the implementation: crash and release behavior is unchanged.
 * Test Change Justification (owner-scope scheduling):
 * Reason category: owner-thread presentation correction.
 * Old behavior/assertion being replaced: advanceUntilIdle drained backgroundScope work only because
 * Unconfined bypassed the owner dispatcher. That operation stops when only background work remains.
 * Coverage preserved by: unchanged timeout, release, retry and event-order assertions; runCurrent
 * drains ready owner work explicitly. CreationScopeAffinityTest independently rejects worker UI access,
 * so this scheduler correction does not fit the test to a broken production implementation.
 * Excludes:
 * - The memo write itself and UI animation frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationOracleContractTest :
    FunSpec({

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
                            readBaselineKeys = { setOf("prev") },
                            prepareEnter = { EnterRequestId(1L) },
                            bindCommittedEnter = { _, _ -> },
                            startPreparedEnter = {},
                            createMemo = { _, _, onAccepted ->
                                onAccepted("new-id")
                                "new-id"
                            },
                            createdMemoRank = { throw IllegalStateException("engine mid-switch") },
                            awaitCreatedItem = { _, _ -> true },
                            revealCreatedItem = { _, _ -> true },
                            cancelPreparedEnter = {},
                            releaseEnter = {},
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
                            readBaselineKeys = { emptySet() },
                            prepareEnter = { EnterRequestId(1L) },
                            bindCommittedEnter = { _, _ -> },
                            startPreparedEnter = {},
                            createMemo = { _, _, onAccepted ->
                                onAccepted("new-id")
                                "new-id"
                            },
                            createdMemoRank = { 0 },
                            awaitCreatedItem = { _, _ -> false },
                            revealCreatedItem = { _, _ -> true },
                            cancelPreparedEnter = {},
                            releaseEnter = {},
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
                            readBaselineKeys = { setOf("prev") },
                            prepareEnter = { EnterRequestId(9L) },
                            bindCommittedEnter = { _, _ -> },
                            startPreparedEnter = {},
                            createMemo = { content, _, onAccepted ->
                                events += "create:$content"
                                onAccepted("id-$content")
                                "id-$content"
                            },
                            createdMemoRank = { 0 },
                            awaitCreatedItem = { _, _ ->
                                events += "await"
                                gate.await()
                                false
                            },
                            revealCreatedItem = { _, _ ->
                                events += "reveal"
                                true
                            },
                            cancelPreparedEnter = { events += "cancel:${it.value}" },
                            releaseEnter = {},
                        ),
                    )

                coordinator.submit("a") shouldBe true
                gate.complete(Unit)
                runCurrent()
                coordinator.submit("b") shouldBe true
                runCurrent()

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
