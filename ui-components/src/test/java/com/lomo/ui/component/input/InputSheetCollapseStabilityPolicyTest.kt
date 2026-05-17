package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputSheet collapse stability policy.
 * - Behavior focus: closing expanded long-form mode must keep the selected mode bar and the active
 *   content layer stable throughout the collapse transition so the UI does not stutter on the first frame.
 * - Observable outcomes: requested collapse state, mode-bar visibility, preview-layer visibility, and focus policy.
 * - Red phase: Fails before the fix because InputSheet uses a single collapse state that immediately
 *   drops the mode bar and preview semantics instead of preserving them through the collapse transition.
 * - Excludes: Compose frame pacing, IME physics, and markdown rendering internals.
 */
class InputSheetCollapseStabilityPolicyTest : UiComponentsFunSpec() {
    init {
        test("expanded edit collapse keeps display mode bar visible during collapse") {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedEdit,
            )

        (requested) shouldBe (InputSheetPresentationState.CollapsingFromEdit)
        (requested.showsDisplayModeToggle()) shouldBe true
        (requested.showsEditorContent()) shouldBe true
        (requested.showsPreviewLayer()) shouldBe false
        (requested.prefersEditorFocus()) shouldBe true
        }
    }

    init {
        test("expanded preview collapse keeps preview layer until compact state settles") {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedPreview,
            )

        (requested) shouldBe (InputSheetPresentationState.CollapsingFromPreview)
        (requested.showsDisplayModeToggle()) shouldBe true
        (requested.showsEditorContent()) shouldBe false
        (requested.showsPreviewLayer()) shouldBe true
        (requested.prefersEditorFocus()) shouldBe false
        }
    }

    init {
        test("switching back to edit still collapses through preview-stable path") {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.SwitchingToEdit,
            )

        (requested) shouldBe (InputSheetPresentationState.CollapsingFromPreview)
        (requested.showsDisplayModeToggle()) shouldBe true
        (requested.showsPreviewLayer()) shouldBe true
        (requested.prefersEditorFocus()) shouldBe false
        }
    }
}
