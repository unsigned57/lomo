package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputSheetOwnerSubmission
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoEditorSubmissionGate as a projection of the owner submission state.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: the editor lock is rebuildable from the owning submission state, so a submission
 *   whose acknowledgement path died still resolves the editor instead of locking the input sheet.
 *
 * Scenarios:
 * - Given a begun submission whose awaiting caller never returns, when the owner publishes
 *   Committed for that submission, then the gate resets and reports the commit.
 * - Given a begun submission whose awaiting caller never returns, when the owner publishes
 *   Failed for that submission, then the gate becomes dismissible without reporting a commit.
 * - Given a begun submission, when the owner publishes a terminal state for a different
 *   submission, then the gate keeps its own submission locked.
 *
 * Observable outcomes:
 * - Gate status, canDismiss, and the committed callback count.
 *
 * TDD proof:
 * - RED on 2026-08-26 because the gate had no owner-state projection at all: its only terminal
 *   entry point was a Boolean returned through composition, so a cancelled acknowledgement left
 *   status Submitting and canDismiss false forever.
 *
 * Excludes:
 * - Compose rendering, repository persistence, and the reveal/animation choreography.
 */
class MemoEditorSubmissionOwnerProjectionTest : AppFunSpec() {
    init {
        test("owner commit resolves a submission whose acknowledgement path never returned") {
            var commits = 0
            val gate = MemoEditorSubmissionGate(onCommitted = { commits += 1 })
            val submissionId = checkNotNull(gate.begin())

            gate.status shouldBe MemoEditorSubmissionStatus.Submitting
            gate.canDismiss shouldBe false

            gate.onOwnerState(MemoEditorSubmissionState.Committed(submissionId))

            gate.status shouldBe MemoEditorSubmissionStatus.Idle
            gate.canDismiss shouldBe true
            commits shouldBe 1
        }

        test("owner failure releases the editor lock without reporting a commit") {
            var commits = 0
            val gate = MemoEditorSubmissionGate(onCommitted = { commits += 1 })
            val submissionId = checkNotNull(gate.begin())

            gate.onOwnerState(MemoEditorSubmissionState.Failed(submissionId))

            gate.status shouldBe MemoEditorSubmissionStatus.Failed
            gate.canDismiss shouldBe true
            commits shouldBe 0
        }

        test("a terminal state for another submission never resolves the active one") {
            var commits = 0
            val gate = MemoEditorSubmissionGate(onCommitted = { commits += 1 })
            val submissionId = checkNotNull(gate.begin())
            val foreignId = MemoEditorSubmissionId(submissionId.value + 1L)

            gate.onOwnerState(MemoEditorSubmissionState.Committed(foreignId))

            gate.status shouldBe MemoEditorSubmissionStatus.Submitting
            gate.canDismiss shouldBe false
            commits shouldBe 0
        }

        test("gate status projects the presentation-facing submission fate") {
            val gate = MemoEditorSubmissionGate(onCommitted = {})
            gate.ownerSubmission shouldBe InputSheetOwnerSubmission.Resolved

            val submissionId = checkNotNull(gate.begin())
            gate.ownerSubmission shouldBe InputSheetOwnerSubmission.Pending

            gate.onOwnerState(MemoEditorSubmissionState.Failed(submissionId))
            gate.ownerSubmission shouldBe InputSheetOwnerSubmission.Rejected
        }

        test("owner idle state leaves an in-flight submission untouched") {
            val gate = MemoEditorSubmissionGate(onCommitted = {})
            gate.begin()

            gate.onOwnerState(MemoEditorSubmissionState.Idle)

            gate.status shouldBe MemoEditorSubmissionStatus.Submitting
        }
    }
}
