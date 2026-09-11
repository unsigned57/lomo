package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputEditor chrome transition state policy.
 * - Behavior focus: long-form edit/preview switching should keep stable chrome hosts mounted while
 *   disabling only the formatting toolbar during preview, and should restore the toolbar as soon as
 *   the sheet starts transitioning back to edit mode.
 * - Observable outcomes: resolved display-mode bar host retention, formatting-toolbar host
 *   retention, visual visibility flags, and interaction enablement across expanded edit, preview,
 *   and transition states.
 * - Red phase: Fails before the fix because preview transitions directly unmount the formatting
 *   toolbar host and keep it hidden through `SwitchingToEdit`, so the bottom action area jumps
 *   instead of recovering smoothly.
 * - Excludes: Compose frame timing, actual alpha interpolation values, and markdown rendering.
 */
class InputEditorChromeTransitionStateTest : UiComponentsFunSpec() {
    init {
        test("expanded edit keeps mode bar and formatting toolbar visible") {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.ExpandedEdit,
                inputText = "Draft",
                hintText = "Hint",
            )

        (state.displayModeBar.keepsHostMounted) shouldBe true
        (state.displayModeBar.isVisible) shouldBe true
        (state.displayModeBar.isInteractive) shouldBe true
        (state.formattingToolbar.keepsHostMounted) shouldBe true
        (state.formattingToolbar.isVisible) shouldBe true
        (state.formattingToolbar.isInteractive) shouldBe true
        }
    }

    init {
        test("switching to preview keeps chrome hosts mounted while hiding formatting toolbar") {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.SwitchingToPreview,
                inputText = "Draft",
                hintText = "Hint",
            )

        (state.displayModeBar.keepsHostMounted) shouldBe true
        (state.displayModeBar.isVisible) shouldBe true
        (state.displayModeBar.isInteractive) shouldBe true
        (state.formattingToolbar.keepsHostMounted) shouldBe true
        (state.formattingToolbar.isVisible) shouldBe false
        (state.formattingToolbar.isInteractive) shouldBe false
        }
    }

    init {
        test("expanded preview keeps mode bar host and disables formatting toolbar") {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.ExpandedPreview,
                inputText = "Draft",
                hintText = "Hint",
            )

        (state.displayModeBar.keepsHostMounted) shouldBe true
        (state.displayModeBar.isVisible) shouldBe true
        (state.displayModeBar.isInteractive) shouldBe true
        (state.formattingToolbar.keepsHostMounted) shouldBe true
        (state.formattingToolbar.isVisible) shouldBe false
        (state.formattingToolbar.isInteractive) shouldBe false
        }
    }

    init {
        test("switching to edit starts restoring formatting toolbar without remounting host") {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.SwitchingToEdit,
                inputText = "Draft",
                hintText = "Hint",
            )

        (state.displayModeBar.keepsHostMounted) shouldBe true
        (state.displayModeBar.isVisible) shouldBe true
        (state.displayModeBar.isInteractive) shouldBe true
        (state.formattingToolbar.keepsHostMounted) shouldBe true
        (state.formattingToolbar.isVisible) shouldBe true
        (state.formattingToolbar.isInteractive) shouldBe true
        }
    }
}
