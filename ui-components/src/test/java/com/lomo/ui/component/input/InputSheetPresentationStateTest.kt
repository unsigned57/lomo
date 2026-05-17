package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputSheet long-form presentation state policy.
 * - Behavior focus: long-form expand/collapse and edit/preview switching must resolve through one
 *   presentation state machine so content visibility, toolbar chrome, and focus intent stay in
 *   sync during transitions.
 * - Observable outcomes: requested and settled presentation states, derived surface motion stage,
 *   editor/preview visibility policy, and editor focus ownership policy.
 * - Red phase: Fails before the fix because InputSheet has no unified presentation state machine
 *   for preview transitions, so preview is not part of the same state model as expand/collapse and
 *   focus release happens out-of-band.
 * - Excludes: Compose animation timing, Android IME internals, and markdown rendering output.
 */
class InputSheetPresentationStateTest : UiComponentsFunSpec() {
    init {
        test("expanded edit transitions into preview through dedicated switching state") {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Preview,
                currentState = InputSheetPresentationState.ExpandedEdit,
            )

        (requested) shouldBe (InputSheetPresentationState.SwitchingToPreview)
        (requested.showsEditorContent()) shouldBe true
        (requested.showsPreviewLayer()) shouldBe true
        (requested.prefersEditorFocus()) shouldBe false
        (requested.surfaceMotionStage()) shouldBe (InputSheetMotionStage.Expanded)
        }
    }

    init {
        test("preview settles as expanded preview and collapse keeps preview stable until compact transition ends") {
        val settledPreview =
            resolveSettledInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Preview,
            )
        val collapsing =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.SwitchingToPreview,
            )

        (settledPreview) shouldBe (InputSheetPresentationState.ExpandedPreview)
        (settledPreview.prefersEditorFocus()) shouldBe false
        (collapsing) shouldBe (InputSheetPresentationState.CollapsingFromPreview)
        (collapsing.showsEditorContent()) shouldBe false
        (collapsing.showsPreviewLayer()) shouldBe true
        (collapsing.prefersEditorFocus()) shouldBe false
        }
    }

    init {
        test("preview returning to edit uses switching state before regaining editor focus") {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedPreview,
            )
        val settled =
            resolveSettledInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Edit,
            )

        (requested) shouldBe (InputSheetPresentationState.SwitchingToEdit)
        (requested.showsEditorContent()) shouldBe true
        (requested.showsPreviewLayer()) shouldBe true
        (requested.prefersEditorFocus()) shouldBe false
        (settled) shouldBe (InputSheetPresentationState.ExpandedEdit)
        (settled.prefersEditorFocus()) shouldBe true
        }
    }
}
