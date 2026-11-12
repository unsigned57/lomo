package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: InputSheet focus and visibility stability policy.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: ensure editor focus request and sheet visibility respond deterministically to focus token changes without stale lock or disabled scrim interception.
 *
 * Scenarios:
 * - Given an active visible editor, when a new focus request token arrives, then focus is requested.
 * - Given an already handled focus token, when evaluated again, then duplicate focus requests are suppressed.
 * - Given a sheet that is hidden or dismissing, when focus is requested, then focus request is rejected.
 * - Given a long-form preview, when a fresh focus token arrives, then focus is refused until editing resumes.
 *
 * Observable outcomes:
 * - shouldRequestInputSheetEditorFocus decision outcome across visible, settled, dismissing, and token states.
 *
 * TDD proof:
 * - Proves focus request gating and visibility lifecycle conditions.
 *
 * Excludes:
 * - Compose frame pacing, IME native platform interaction, and hardware keyboard state.
 *
 * Test Change Justification:
 * Reason category: domain contract change — the presentation state machine was removed.
 * Old behavior/assertion being replaced: focus gating read InputSheetPresentationState.prefersEditorFocus()
 *   with presentationState = CompactEdit.
 * Why old assertion is no longer correct: that type no longer exists; focus now follows whether the committed
 *   display mode shows editable text (editorAcceptsFocus).
 * Coverage preserved by: every existing scenario keeps its outcome with editorAcceptsFocus = true (CompactEdit's
 *   old answer); the new preview scenario covers the refusal that SwitchingToPreview/ExpandedPreview provided.
 * Why this is not fitting the test to the implementation: outcomes are accept/reject decisions for a fresh token.
 */
class InputSheetFocusStabilityTest : UiComponentsFunSpec() {
    init {
        test("given visible and settled sheet when fresh focus token arrives then focus is accepted") {
            val shouldFocus =
                shouldRequestInputSheetEditorFocus(
                    isSheetVisible = true,
                    isSheetEntrySettled = true,
                    editorAcceptsFocus = true,
                    isRecording = false,
                    isDismissing = false,
                    focusRequestToken = 2L,
                    lastHandledFocusRequestToken = 1L,
                )

            shouldFocus shouldBe true
        }

        test("given already handled focus token when checked then duplicate focus request is suppressed") {
            val shouldFocus =
                shouldRequestInputSheetEditorFocus(
                    isSheetVisible = true,
                    isSheetEntrySettled = true,
                    editorAcceptsFocus = true,
                    isRecording = false,
                    isDismissing = false,
                    focusRequestToken = 2L,
                    lastHandledFocusRequestToken = 2L,
                )

            shouldFocus shouldBe false
        }

        test("given invisible or dismissing sheet when checked then focus request is rejected") {
            shouldRequestInputSheetEditorFocus(
                isSheetVisible = false,
                isSheetEntrySettled = true,
                editorAcceptsFocus = true,
                isRecording = false,
                isDismissing = false,
                focusRequestToken = 2L,
                lastHandledFocusRequestToken = 1L,
            ) shouldBe false

            shouldRequestInputSheetEditorFocus(
                isSheetVisible = true,
                isSheetEntrySettled = true,
                editorAcceptsFocus = true,
                isRecording = false,
                isDismissing = true,
                focusRequestToken = 2L,
                lastHandledFocusRequestToken = 1L,
            ) shouldBe false
        }

        test("given a long-form preview when a fresh focus token arrives then focus is refused") {
            shouldRequestInputSheetEditorFocus(
                isSheetVisible = true,
                isSheetEntrySettled = true,
                editorAcceptsFocus = false,
                isRecording = false,
                isDismissing = false,
                focusRequestToken = 3L,
                lastHandledFocusRequestToken = 2L,
            ) shouldBe false
        }
    }
}
