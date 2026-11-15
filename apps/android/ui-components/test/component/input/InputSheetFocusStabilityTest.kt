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
 * - shouldRequestInputSheetEditorFocus decision outcome across visible, dismissing, preview, and token states.
 * - Initial InputSheetSessionState visibility allows entry animation and IME focus to launch concurrently.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.InputSheetFocusStabilityTest'`
 *   fails because `shouldRequestInputSheetEditorFocus` still takes `isSheetEntrySettled` (forcing IME
 *   to wait until slide-in animation settles) and `InputSheetSessionState.isSheetVisible` starts false.
 * - GREEN: passes after removing the `isSheetEntrySettled` gate and starting `isSheetVisible = true`.
 *
 * Excludes:
 * - Compose frame pacing, IME native platform interaction, and hardware keyboard state.
 *
 * Test Change Justification:
 * Reason category: remove animation-settle sequential blocking in IME popup path.
 * Old behavior/assertion being replaced: `shouldRequestInputSheetEditorFocus` gated on
 *   `isSheetEntrySettled = true`, delaying editor focus and IME show until enter animation ended.
 * Why old assertion is no longer correct: the `isSheetEntrySettled` parameter is deleted so editor
 *   focus and keyboard popup run concurrently with the sheet enter animation on first frame.
 * Coverage preserved by: visible, duplicate-token, invisible, dismissing, and preview-mode gating
 *   assertions are all retained verbatim minus the deleted parameter, plus immediate visibility init.
 * Why this is not fitting the test to the implementation: asserts immediate entry concurrency
 *   against the deleted settle bottleneck and preserves every guard condition.
 */
class InputSheetFocusStabilityTest : UiComponentsFunSpec() {
    init {
        test(
            "given visible sheet when fresh focus token arrives then focus is accepted immediately without settle wait",
        ) {
            val shouldFocus =
                shouldRequestInputSheetEditorFocus(
                    isSheetVisible = true,
                    editorAcceptsFocus = true,
                    isRecording = false,
                    isDismissing = false,
                    focusRequestToken = 2L,
                    lastHandledFocusRequestToken = 1L,
                )

            shouldFocus shouldBe true
        }

        test(
            "given fresh InputSheetSessionState then sheet starts visible for concurrent frame-0 enter and IME launch",
        ) {
            InputSheetSessionState(initialInputText = "").isSheetVisible shouldBe true
        }

        test("given already handled focus token when checked then duplicate focus request is suppressed") {
            val shouldFocus =
                shouldRequestInputSheetEditorFocus(
                    isSheetVisible = true,
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
                editorAcceptsFocus = true,
                isRecording = false,
                isDismissing = false,
                focusRequestToken = 2L,
                lastHandledFocusRequestToken = 1L,
            ) shouldBe false

            shouldRequestInputSheetEditorFocus(
                isSheetVisible = true,
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
                editorAcceptsFocus = false,
                isRecording = false,
                isDismissing = false,
                focusRequestToken = 3L,
                lastHandledFocusRequestToken = 2L,
            ) shouldBe false
        }
    }
}
