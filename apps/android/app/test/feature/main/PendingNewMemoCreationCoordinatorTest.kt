/*
 * Behavior Contract:
 * - Unit under test: pending new-memo creation coordinator.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: retain a typed editor submission identity while new-memo creation waits for list
 *   positioning, and reject overlap until the matching request is consumed or canceled.
 *
 * Scenarios:
 * - Given a submitted request, when it waits/consumes/cancels, then content, submission identity,
 *   and backfill time remain attached to the same request.
 * - Given one pending request, when another is submitted, then overlap is rejected.
 *
 * Observable outcomes:
 * - Pending request snapshot, typed submission id, metadata, overlap result and consume/cancel state.
 *
 * TDD proof:
 * - RED on 2026-08-09 because pending creation carried no editor submission identity, so the
 *   eventual durable commit could not acknowledge the exact sheet submission that initiated it.
 *
 * Excludes:
 * - Compose recomposition, LazyList animation internals, and repository persistence.
 *
 * Test Change Justification:
 * - Reason category: typed editor submission identity lifecycle.
 * - Old behavior/assertion being replaced: coordinator without typed submission id tracking.
 * - Why old assertion is no longer correct: editor submissions require deterministic acknowledgement by id.
 * - Coverage preserved by: all pending creation, conflict rejection, and consumption scenarios remain fully tested.
 * - Why this is not fitting the test to the implementation: verifies submission identity conservation across async hops.
 */

package com.lomo.app.feature.main

import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
class PendingNewMemoCreationCoordinatorTest : AppFunSpec() {
    init {
        test("submit stores first request and rejects overlap until consumed") {
            val coordinator = PendingNewMemoCreationCoordinator()

            val firstRequest = coordinator.submit(MemoEditorSubmissionId(1L), "first memo")
            val secondRequest = coordinator.submit(MemoEditorSubmissionId(2L), "second memo")

            (firstRequest) shouldBe (PendingNewMemoCreationRequest(
                    requestId = 1L,
                    submissionId = MemoEditorSubmissionId(1L),
                    content = "first memo",
                ))
            (coordinator.pendingRequest) shouldBe (firstRequest)
            (secondRequest) shouldBe null
            (coordinator.pendingRequest) shouldBe (firstRequest)
        }
        test("consume clears only the matching pending request") {
            val coordinator = PendingNewMemoCreationCoordinator()
            val firstRequest = checkNotNull(coordinator.submit(MemoEditorSubmissionId(3L), "first memo"))

            (coordinator.consume(requestId = firstRequest.requestId + 1L)) shouldBe null
            (coordinator.pendingRequest) shouldBe (firstRequest)
            (coordinator.consume(requestId = firstRequest.requestId)) shouldBe (firstRequest)
            (coordinator.pendingRequest) shouldBe null
        }
        test("submit stores optional backfill timestamp") {
            val coordinator = PendingNewMemoCreationCoordinator()

            val request =
                coordinator.submit(
                    submissionId = MemoEditorSubmissionId(4L),
                    content = "backfilled memo",
                    timestampMillis = 1_777_777_777_000L,
                )

            (request) shouldBe (PendingNewMemoCreationRequest(
                    requestId = 1L,
                    submissionId = MemoEditorSubmissionId(4L),
                    content = "backfilled memo",
                    timestampMillis = 1_777_777_777_000L,
                ))
            (coordinator.pendingRequest) shouldBe (request)
        }
        test("cancel clears the matching request and allows the next submit") {
            val coordinator = PendingNewMemoCreationCoordinator()
            val firstRequest = checkNotNull(coordinator.submit(MemoEditorSubmissionId(5L), "first memo"))

            coordinator.cancel(requestId = firstRequest.requestId)

            val secondRequest = coordinator.submit(MemoEditorSubmissionId(6L), "second memo")

            (secondRequest) shouldBe (PendingNewMemoCreationRequest(
                    requestId = firstRequest.requestId + 1L,
                    submissionId = MemoEditorSubmissionId(6L),
                    content = "second memo",
                ))
            (coordinator.pendingRequest) shouldBe (secondRequest)
        }
    }

}
