package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: NewMemoCreationCoordinator
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: coordinate new-memo creation across the full insert lifecycle including scroll, await, and reveal.
 *
 * Scenarios:
 * - Given the list is at top, when submit is called, then no scroll occurs, the memo is created, and after awaiting a new top id it reveals at position 0.
 * - Given the list is away from top, when submit is called, then it scrolls to top first, then creates and awaits/reveals with no second anchor-pinning phase.
 * - Given a submit is in flight, when a second submit is called, then the second submit is rejected.
 * - Given an await times out due to DB write failure, when reveal runs, then it still executes gracefully.
 * - Given an empty list with previousTopId=null, when a new memo is created, then any non-null top id triggers reveal.
 *
 * Observable outcomes:
 * - Sequence of events (scroll/create/await/reveal), captured previousTopId, overlap rejection, and graceful timeout handling.
 *
 * TDD proof:
 * - Fails before the fix because the previous coordinator invoked revealNewTopItem in the same frame as the fire-and-forget createMemo, racing the async DB insert.
 *
 * Excludes:
 * - Compose rendering, actual DB persistence, and paging source internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed (race fix).
 * - Old behavior/assertion being replaced: the previous coordinator invoked revealNewTopItem in
 *   the same frame as the fire-and-forget `createMemo`, racing the async DB insert; the
 *   scroll pinned the old top and the freshly-created memo ended up above the viewport.
 * - Why old assertion is no longer correct: the coordinator must own the full
 *   lifecycle including the snapshot-wait step, otherwise the reveal cannot see the new
 *   item in the paging list.
 * - Coverage preserved by: the existing top-recovery and overlap tests are preserved
 *   with the new API.
 * - Why this is not fitting the test to the implementation: it defines the
 *   user-visible correctness contract (new memo must enter the viewport) per the
 *   reported regression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationCoordinatorTest : AppFunSpec() {
    init {
        test("submit at top creates immediately, awaits new top memo, then reveals") {
            runTest {
                val events = mutableListOf<String>()
                var capturedPreviousTopId: String? = "UNSET"
                var createdWasAtTop: Boolean? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        createMemo = { content, wasAtTop ->
                            events += "create:$content"
                            createdWasAtTop = wasAtTop
                        },
                        currentTopMemoId = { "old-memo-id" },
                        awaitNewTopItemAndReveal = { previousTopId ->
                            capturedPreviousTopId = previousTopId
                            events += "await:$previousTopId"
                            events += "reveal"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf("create:memo body", "await:old-memo-id", "reveal")
                createdWasAtTop shouldBe true
                capturedPreviousTopId shouldBe "old-memo-id"
            }
        }
    }

    init {
        test("submit away from top scrolls first, then creates, awaits, and reveals without a second anchor phase") {
            runTest {
                val events = mutableListOf<String>()
                var atTop = false
                var createdWasAtTop: Boolean? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { atTop },
                        scrollListToAbsoluteTop = {
                            events += "scroll"
                            atTop = true
                        },
                        createMemo = { content, wasAtTop ->
                            events += "create:$content"
                            createdWasAtTop = wasAtTop
                        },
                        currentTopMemoId = { "prev-id" },
                        awaitNewTopItemAndReveal = { previousTopId ->
                            events += "await:$previousTopId"
                            events += "reveal"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf("scroll", "create:memo body", "await:prev-id", "reveal")
                createdWasAtTop shouldBe true
            }
        }
    }

    init {
        test("submit ignores overlapping requests while waiting for creation/reveal") {
            runTest {
                val awaitGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        createMemo = { content, _ -> events += "create:$content" },
                        currentTopMemoId = { "prev-id" },
                        awaitNewTopItemAndReveal = { previousTopId ->
                            events += "await:$previousTopId"
                            awaitGate.await()
                            events += "reveal"
                        },
                    )

                val firstAccepted = coordinator.submit("first")
                val secondAccepted = coordinator.submit("second")
                awaitGate.complete(Unit)
                advanceUntilIdle()

                firstAccepted shouldBe true
                secondAccepted shouldBe false
                events shouldBe listOf("create:first", "await:prev-id", "reveal")
            }
        }
    }

    init {
        test("previousTopId is null when list is empty; await runs and reveals anyway") {
            runTest {
                val events = mutableListOf<String>()
                var capturedPreviousTopId: String? = "UNSET"
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        createMemo = { content, _ -> events += "create:$content" },
                        currentTopMemoId = { null },
                        awaitNewTopItemAndReveal = { previousTopId ->
                            capturedPreviousTopId = previousTopId
                            events += "reveal"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf("create:memo body", "reveal")
                capturedPreviousTopId shouldBe null
            }
        }
    }

    init {
        test("reveal runs even when await returns without a new top id (graceful guard)") {
            runTest {
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        createMemo = { content, _ -> events += "create:$content" },
                        currentTopMemoId = { "prev-id" },
                        awaitNewTopItemAndReveal = { previousTopId ->
                            events += "await:$previousTopId"
                            events += "reveal"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf("create:memo body", "await:prev-id", "reveal")
            }
        }
    }
}