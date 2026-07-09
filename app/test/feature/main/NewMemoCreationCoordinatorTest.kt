package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: NewMemoCreationCoordinator
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: coordinate new-memo creation so the list enter request is based on a real loaded head baseline before the async insert mutates the visible head.
 *
 * Scenarios:
 * - Given the list is at top, when submit is called, then the coordinator awaits a loaded top baseline, prepares pending head enter, creates, awaits, and reveals.
 * - Given the list is away from top, when submit is called, then it scrolls to top, awaits the post-scroll baseline, prepares pending enter, creates, awaits, and reveals.
 * - Given a submit is in flight, when a second submit is called, then the second submit is rejected.
 * - Given awaiting a new top id times out, when the lifecycle finishes, then the prepared enter request is canceled.
 * - Given an empty list baseline, when a new memo is created, then any non-null top id can reveal.
 * - Given the top baseline is not yet loaded, when submit is called, then create is not invoked until the baseline becomes available.
 *
 * Observable outcomes:
 * - Sequence of events, captured baseline, prepared request id cancellation, overlap rejection, and reveal target id.
 *
 * TDD proof:
 * - Fails before the fix because NewMemoCreationCoordinator accepts a nullable previousTopId, so
 *   an unloaded head is treated as an empty list and the pending request can be consumed too early.
 *
 * Excludes:
 * - Compose rendering, actual DB persistence, and paging source internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed (new memo enter race fix).
 * - Old behavior/assertion being replaced: tests accepted a nullable previousTopId and
 *   asserted create/await/reveal sequencing without proving that the head item was loaded.
 * - Why old assertion is no longer correct: null conflated an unloaded paging head with a
 *   truly empty list, so the coordinator could consume an enter request before the top
 *   baseline was known.
 * - Coverage preserved by: existing at-top, away-from-top, overlap, empty-list, and
 *   timeout scenarios are retained with explicit HeadEnterBaseline outcomes.
 * - Why this is not fitting the test to the implementation: the assertions define the
 *   user-visible contract that a newly created memo enters from a real loaded top
 *   baseline instead of jumping outside the viewport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationCoordinatorTest : AppFunSpec() {
    init {
        test("submit at top awaits a loaded baseline before prepare, create, await, and reveal") {
            runTest {
                val events = mutableListOf<String>()
                var capturedBaseline: HeadEnterBaseline? = null
                var createdWasAtTop: Boolean? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        awaitTopBaseline = {
                            events += "baseline"
                            HeadEnterBaseline.ExistingHead("old-memo-id")
                        },
                        prepareNewTopEnter = { baseline ->
                            capturedBaseline = baseline
                            events += "prepare:$baseline"
                            EnterRequestId(1L)
                        },
                        createMemo = { content, wasAtTop ->
                            events += "create:$content"
                            createdWasAtTop = wasAtTop
                        },
                        awaitNewTopItem = { baseline ->
                            events += "await:$baseline"
                            "new-memo-id"
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=old-memo-id)",
                    "create:memo body",
                    "await:ExistingHead(id=old-memo-id)",
                    "reveal:new-memo-id",
                )
                createdWasAtTop shouldBe true
                capturedBaseline shouldBe HeadEnterBaseline.ExistingHead("old-memo-id")
            }
        }

        test("submit away from top scrolls first, then awaits the top baseline observed after scrolling") {
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
                        awaitTopBaseline = {
                            events += "baseline"
                            HeadEnterBaseline.ExistingHead("prev-id")
                        },
                        prepareNewTopEnter = { baseline ->
                            events += "prepare:$baseline"
                            EnterRequestId(2L)
                        },
                        createMemo = { content, wasAtTop ->
                            events += "create:$content"
                            createdWasAtTop = wasAtTop
                        },
                        awaitNewTopItem = { baseline ->
                            events += "await:$baseline"
                            "new-id"
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "scroll",
                    "baseline",
                    "prepare:ExistingHead(id=prev-id)",
                    "create:memo body",
                    "await:ExistingHead(id=prev-id)",
                    "reveal:new-id",
                )
                createdWasAtTop shouldBe true
            }
        }

        test("submit ignores overlapping requests while waiting for creation and reveal") {
            runTest {
                val awaitGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        awaitTopBaseline = {
                            events += "baseline"
                            HeadEnterBaseline.ExistingHead("prev-id")
                        },
                        prepareNewTopEnter = { baseline ->
                            events += "prepare:$baseline"
                            EnterRequestId(3L)
                        },
                        createMemo = { content, _ -> events += "create:$content" },
                        awaitNewTopItem = { baseline ->
                            events += "await:$baseline"
                            awaitGate.await()
                            "new-id"
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val firstAccepted = coordinator.submit("first")
                val secondAccepted = coordinator.submit("second")
                awaitGate.complete(Unit)
                advanceUntilIdle()

                firstAccepted shouldBe true
                secondAccepted shouldBe false
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=prev-id)",
                    "create:first",
                    "await:ExistingHead(id=prev-id)",
                    "reveal:new-id",
                )
            }
        }

        test("empty-list baseline prepares enter and reveal uses returned top id") {
            runTest {
                val events = mutableListOf<String>()
                var capturedBaseline: HeadEnterBaseline? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        awaitTopBaseline = {
                            events += "baseline"
                            HeadEnterBaseline.EmptyList
                        },
                        prepareNewTopEnter = { baseline ->
                            capturedBaseline = baseline
                            events += "prepare:$baseline"
                            EnterRequestId(4L)
                        },
                        createMemo = { content, _ -> events += "create:$content" },
                        awaitNewTopItem = { baseline ->
                            events += "await:$baseline"
                            "first-id"
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:EmptyList",
                    "create:memo body",
                    "await:EmptyList",
                    "reveal:first-id",
                )
                capturedBaseline shouldBe HeadEnterBaseline.EmptyList
            }
        }

        test("await timeout cancels the prepared enter request without revealing") {
            runTest {
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        awaitTopBaseline = {
                            events += "baseline"
                            HeadEnterBaseline.ExistingHead("prev-id")
                        },
                        prepareNewTopEnter = { baseline ->
                            events += "prepare:$baseline"
                            EnterRequestId(5L)
                        },
                        createMemo = { content, _ -> events += "create:$content" },
                        awaitNewTopItem = { baseline ->
                            events += "await:$baseline"
                            null
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=prev-id)",
                    "create:memo body",
                    "await:ExistingHead(id=prev-id)",
                    "cancel:5",
                )
            }
        }

        test("submit waits for a top baseline before consuming the create request") {
            runTest {
                val baseline = CompletableDeferred<HeadEnterBaseline>()
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { events += "scroll" },
                        awaitTopBaseline = {
                            events += "await-baseline"
                            baseline.await()
                        },
                        prepareNewTopEnter = { loadedBaseline ->
                            events += "prepare:$loadedBaseline"
                            EnterRequestId(6L)
                        },
                        createMemo = { content, _ -> events += "create:$content" },
                        awaitNewTopItem = { loadedBaseline ->
                            events += "await-new:$loadedBaseline"
                            "new-id"
                        },
                        revealNewTopItem = { newTopId ->
                            events += "reveal:$newTopId"
                        },
                        cancelPreparedEnter = { requestId ->
                            events += "cancel:${requestId.value}"
                        },
                    )

                val accepted = coordinator.submit("memo body")
                runCurrent()

                accepted shouldBe true
                events shouldBe listOf("await-baseline")

                baseline.complete(HeadEnterBaseline.ExistingHead("old-id"))
                advanceUntilIdle()

                events shouldBe listOf(
                    "await-baseline",
                    "prepare:ExistingHead(id=old-id)",
                    "create:memo body",
                    "await-new:ExistingHead(id=old-id)",
                    "reveal:new-id",
                )
            }
        }
    }
}
