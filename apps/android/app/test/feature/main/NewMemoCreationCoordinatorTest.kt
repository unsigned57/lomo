package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
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
 * - Capability: coordinate new-memo creation so the list enter request is based on the currently
 *   loaded head baseline read before the durable commit, and the new-head wait only runs when the
 *   owning query engine ranks the committed memo as the list head.
 *
 * Scenarios:
 * - Given the list is at top, when submit is called, then the coordinator reads the current
 *   baseline synchronously, prepares the enter, and creates before waiting on the new head.
 * - Given the list is away from top, when submit is called, then durable creation starts before
 *   presentation scrolling and the post-commit animation baseline was read beforehand.
 * - Given a submit is in flight, when a second submit is called, then the second submit is rejected.
 * - Given awaiting a new top id times out, when the lifecycle finishes, then the prepared enter
 *   request is canceled.
 * - Given an empty list baseline, when a new memo is created, then any non-null top id can reveal.
 * - Given no top baseline is currently loaded, when submit is called, then create runs immediately
 *   without waiting on the viewport.
 * - Given the committed memo does not rank as the active list head, when creation commits, then the
 *   bounded new-head wait and reveal are skipped and the prepared enter is canceled.
 * - Given durable create fails, when submit runs, then baseline/rank/reveal work is skipped and the
 *   submission slot is released for retry.
 *
 * Observable outcomes:
 * - Sequence of events, captured baseline, prepared request id cancellation, overlap rejection,
 *   consulted memo id, and reveal target id.
 *
 * TDD proof:
 * - Fails while the baseline is awaited inside a bounded suspend window before commit: the commit
 *   is gated on a viewport condition and a late sample masquerades as the pre-commit baseline.
 * - Fails while the new-head wait runs without an engine membership/rank oracle: a memo the active
 *   spec cannot surface burns the full reveal timeout.
 *
 * Excludes:
 * - Compose rendering, actual DB persistence, and paging source internals.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: suspended awaitTopBaseline sampling with a 250ms viewport timeout and an unconditional bounded head wait.
 * - Why old assertion is no longer correct: durable commit must not be gated by viewport timing; the baseline is read synchronously and the head wait only runs when the engine rank oracle marks the memo as head.
 * - Coverage preserved by: rewritten contract cases for immediate create, rank-gated wait, timeout cancellation, and overlap rejection.
 * - Why this is not fitting the test to the implementation: the spec changed to commit-first, rank-gated reveal per the audit contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationCoordinatorTest : AppFunSpec() {
    init {
        test("submit at top reads the current baseline, prepares enter, creates, ranks and reveals") {
            runTest {
                val events = mutableListOf<String>()
                var capturedBaseline: HeadEnterBaseline? = null
                var createdWasAtTop: Boolean? = null
                var rankedMemoId: String? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
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
                                "committed-memo-id"
                            },
                            newHeadRank = { memoId ->
                                rankedMemoId = memoId
                                events += "rank:$memoId"
                                0
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
                        ),
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=old-memo-id)",
                    "create:memo body",
                    "rank:committed-memo-id",
                    "await:ExistingHead(id=old-memo-id)",
                    "reveal:new-memo-id",
                )
                createdWasAtTop shouldBe true
                capturedBaseline shouldBe HeadEnterBaseline.ExistingHead("old-memo-id")
                rankedMemoId shouldBe "committed-memo-id"
            }
        }

        test("submit away from top starts durable creation before presentation scrolling") {
            runTest {
                val events = mutableListOf<String>()
                var atTop = false
                var createdWasAtTop: Boolean? = null
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { atTop },
                            scrollListToAbsoluteTop = {
                                events += "scroll"
                                atTop = true
                            },
                            readTopBaseline = {
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
                                "new-id"
                            },
                            newHeadRank = { 0 },
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
                        ),
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=prev-id)",
                    "create:memo body",
                    "scroll",
                    "await:ExistingHead(id=prev-id)",
                    "reveal:new-id",
                )
                createdWasAtTop shouldBe false
            }
        }

        test("submit ignores overlapping requests while waiting for creation and reveal") {
            runTest {
                val awaitGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                HeadEnterBaseline.ExistingHead("prev-id")
                            },
                            prepareNewTopEnter = { baseline ->
                                events += "prepare:$baseline"
                                EnterRequestId(3L)
                            },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                "new-id"
                            },
                            newHeadRank = { 0 },
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
                        ),
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
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                HeadEnterBaseline.EmptyList
                            },
                            prepareNewTopEnter = { baseline ->
                                capturedBaseline = baseline
                                events += "prepare:$baseline"
                                EnterRequestId(4L)
                            },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                "first-id"
                            },
                            newHeadRank = { 0 },
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
                        ),
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
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                HeadEnterBaseline.ExistingHead("prev-id")
                            },
                            prepareNewTopEnter = { baseline ->
                                events += "prepare:$baseline"
                                EnterRequestId(5L)
                            },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                "new-id"
                            },
                            newHeadRank = { 0 },
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
                        ),
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

        test("submit creates immediately when no top baseline is currently loaded") {
            runTest {
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                null
                            },
                            prepareNewTopEnter = { error("no baseline means no animation preparation") },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                "new-id"
                            },
                            newHeadRank = { error("rank oracle only feeds the baseline reveal path") },
                            awaitNewTopItem = { error("no baseline means no head wait") },
                            revealNewTopItem = { error("no baseline means no reveal") },
                            cancelPreparedEnter = { error("no enter request was prepared") },
                        ),
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf("baseline", "create:memo body")
            }
        }

        test("a committed memo that cannot be the active head skips the bounded new-head wait") {
            runTest {
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                HeadEnterBaseline.ExistingHead("prev-id")
                            },
                            prepareNewTopEnter = { baseline ->
                                events += "prepare:$baseline"
                                EnterRequestId(6L)
                            },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                "filtered-out-id"
                            },
                            newHeadRank = { memoId ->
                                events += "rank:$memoId"
                                null
                            },
                            awaitNewTopItem = { error("non-head memo must not trigger the bounded wait") },
                            revealNewTopItem = { error("non-head memo must not reveal") },
                            cancelPreparedEnter = { requestId ->
                                events += "cancel:${requestId.value}"
                            },
                        ),
                    )

                val accepted = coordinator.submit("memo body")
                advanceUntilIdle()

                accepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare:ExistingHead(id=prev-id)",
                    "create:memo body",
                    "rank:filtered-out-id",
                    "cancel:6",
                )
            }
        }

        test("a committed memo ranked behind the head skips the bounded new-head wait") {
            runTest {
                val events = mutableListOf<String>()
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                HeadEnterBaseline.ExistingHead("pinned-id")
                            },
                            prepareNewTopEnter = { EnterRequestId(7L) },
                            createMemo = { _, _ -> "behind-pinned-id" },
                            newHeadRank = { memoId ->
                                events += "rank:$memoId"
                                3
                            },
                            awaitNewTopItem = { error("a non-head member must not trigger the bounded wait") },
                            revealNewTopItem = { error("a non-head member must not reveal") },
                            cancelPreparedEnter = { requestId ->
                                events += "cancel:${requestId.value}"
                            },
                        ),
                    )

                coordinator.submit("memo body")
                advanceUntilIdle()

                events shouldBe listOf("rank:behind-pinned-id", "cancel:7")
            }
        }

        test("failed durable create skips baseline, rank and reveal work and releases the slot") {
            runTest {
                val events = mutableListOf<String>()
                var createSucceeds = false
                val coordinator =
                    NewMemoCreationCoordinator<String>(
                        NewMemoCreationCoordinatorDependencies<String>(
                            scope = backgroundScope,
                            isListAtAbsoluteTop = { true },
                            scrollListToAbsoluteTop = { events += "scroll" },
                            readTopBaseline = {
                                events += "baseline"
                                HeadEnterBaseline.EmptyList
                            },
                            prepareNewTopEnter = {
                                events += "prepare"
                                EnterRequestId(8L)
                            },
                            createMemo = { content, _ ->
                                events += "create:$content"
                                if (createSucceeds) "new-id" else null
                            },
                            newHeadRank = {
                                events += "rank"
                                0
                            },
                            awaitNewTopItem = {
                                events += "await"
                                "new-id"
                            },
                            revealNewTopItem = { events += "reveal:$it" },
                            cancelPreparedEnter = { requestId ->
                                events += "cancel:${requestId.value}"
                            },
                        ),
                    )

                val firstAccepted = coordinator.submit("first body")
                advanceUntilIdle()

                firstAccepted shouldBe true
                events shouldBe listOf("baseline", "prepare", "create:first body", "cancel:8")

                createSucceeds = true
                val secondAccepted = coordinator.submit("retry body")
                advanceUntilIdle()

                secondAccepted shouldBe true
                events shouldBe listOf(
                    "baseline",
                    "prepare",
                    "create:first body",
                    "cancel:8",
                    "baseline",
                    "prepare",
                    "create:retry body",
                    "rank",
                    "await",
                    "reveal:new-id",
                )
            }
        }
    }
}
