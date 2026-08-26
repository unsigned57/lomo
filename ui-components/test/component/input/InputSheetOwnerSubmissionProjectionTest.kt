package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: input-sheet submission lock as a projection of the owning submission state.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P0.
 * - Capability: the sheet's submission lock is rebuildable from the owner, so a submission whose
 *   acknowledgement coroutine died still releases the sheet instead of freezing the editor.
 *
 * Scenarios:
 * - Given an accepted submission whose acknowledgement never returns, when the owner rejects the
 *   submission, then the lock is released and the withdrawn sheet is restored.
 * - Given an accepted submission whose acknowledgement never returns, when the owner reports the
 *   submission committed, then the lock is released and the sheet stays withdrawn.
 * - Given the owner still reports an active submission, then the lock is kept.
 * - Given no local submission is in flight, when the owner reports a terminal state, then sheet
 *   presentation is left untouched.
 *
 * Observable outcomes:
 * - isSubmitting, isSheetVisible, isDismissing.
 *
 * TDD proof:
 * - RED on 2026-08-26 because the lock had no owner projection: its only release path ran in the
 *   sheet's own composition scope, so a cancelled acknowledgement left isSubmitting true forever.
 *
 * Excludes:
 * - Compose rendering, keyboard behavior, and durable memo storage.
 */
class InputSheetOwnerSubmissionProjectionTest : UiComponentsFunSpec() {
    init {
        test("owner failure releases a lock whose acknowledgement never returned and restores the sheet") {
            val session =
                InputSheetSessionState(initialInputText = "draft").apply {
                    isSubmitting = true
                    pendingSubmissionTriggerText = "draft"
                    submissionLockSourceText = "draft"
                    isDismissing = true
                    isSheetVisible = false
                }

            applyInputSheetOwnerSubmission(session, InputSheetOwnerSubmission.Rejected)

            session.isSubmitting shouldBe false
            session.isDismissing shouldBe false
            session.isSheetVisible shouldBe true
        }

        test("owner commit releases the lock and keeps the sheet withdrawn") {
            val session =
                InputSheetSessionState(initialInputText = "draft").apply {
                    isSubmitting = true
                    isDismissing = true
                    isSheetVisible = false
                }

            applyInputSheetOwnerSubmission(session, InputSheetOwnerSubmission.Resolved)

            session.isSubmitting shouldBe false
            session.isSheetVisible shouldBe false
        }

        test("a pending owner submission keeps the lock") {
            val session =
                InputSheetSessionState(initialInputText = "draft").apply {
                    isSubmitting = true
                    isSheetVisible = false
                }

            applyInputSheetOwnerSubmission(session, InputSheetOwnerSubmission.Pending)

            session.isSubmitting shouldBe true
            session.isSheetVisible shouldBe false
        }

        test("a terminal owner state never revives a sheet that holds no submission") {
            val session =
                InputSheetSessionState(initialInputText = "draft").apply {
                    isSheetVisible = false
                    isDismissing = true
                }

            applyInputSheetOwnerSubmission(session, InputSheetOwnerSubmission.Rejected)

            session.isSubmitting shouldBe false
            session.isSheetVisible shouldBe false
            session.isDismissing shouldBe true
        }
    }
}
