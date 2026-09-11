package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputEditor long-form chrome state policy.
 * - Behavior focus: long-form entry should live in the toolbar as an icon action, and placeholder text should appear only for compact empty input.
 * - Observable outcomes: resolved toggle icon mode, standalone-mode-bar visibility, and placeholder visibility for compact and expanded editor states.
 * - Red phase: Fails before the fix because the editor renders a separate long-form mode bar and still shows placeholder text after expanding an empty draft.
 * - Excludes: Compose layout rendering, animation timing, and memo submission behavior.
 */
class InputEditorChromeStateTest : UiComponentsFunSpec() {
    init {
        test("compact empty editor uses expand toolbar icon and shows placeholder") {
        val state =
            resolveInputEditorChromeState(
                isExpanded = false,
                displayMode = InputEditorDisplayMode.Edit,
                inputText = "",
                hintText = "Write something long",
            )

        (state.toggleIcon) shouldBe (InputEditorToggleIcon.Expand)
        (state.showsStandaloneModeBar) shouldBe false
        (state.showsPlaceholder) shouldBe true
        (state.showsDisplayModeToggle) shouldBe false
        (state.showsFormattingToolbar) shouldBe true
        (state.showsPreviewContent) shouldBe false
        }
    }

    init {
        test("expanded empty editor uses collapse toolbar icon and hides placeholder") {
        val state =
            resolveInputEditorChromeState(
                isExpanded = true,
                displayMode = InputEditorDisplayMode.Edit,
                inputText = "",
                hintText = "Write something long",
            )

        (state.toggleIcon) shouldBe (InputEditorToggleIcon.Collapse)
        (state.showsStandaloneModeBar) shouldBe false
        (state.showsPlaceholder) shouldBe false
        (state.showsDisplayModeToggle) shouldBe true
        (state.showsFormattingToolbar) shouldBe true
        (state.showsPreviewContent) shouldBe false
        }
    }

    init {
        test("expanded preview hides editor placeholder and switches content path") {
        val state =
            resolveInputEditorChromeState(
                isExpanded = true,
                displayMode = InputEditorDisplayMode.Preview,
                inputText = "",
                hintText = "Write something long",
            )

        (state.toggleIcon) shouldBe (InputEditorToggleIcon.Collapse)
        (state.showsPlaceholder) shouldBe false
        (state.showsDisplayModeToggle) shouldBe true
        (state.showsFormattingToolbar) shouldBe false
        (state.showsPreviewContent) shouldBe true
        }
    }
}
