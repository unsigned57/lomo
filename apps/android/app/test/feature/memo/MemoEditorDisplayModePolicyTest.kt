package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputEditorDisplayMode
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given an expanded editor, when edit and preview modes change, then focus is requested only on returning
 *   to edit.
 * - Given preview mode, when the session collapses or closes, then its display mode resets to edit.
 * - Unit under test: MemoEditorController preview display-mode policy.
 * - Capability: long-form editor sessions should default to edit mode, allow switching into preview without re-requesting focus, re-request focus when returning to edit, and reset preview mode on collapse or close.
 * - Observable outcomes: displayMode value and focusRequestToken progression across preview transitions, collapse, and close.
 * - TDD proof: Fails before the fix because MemoEditorController has no preview display-mode state, cannot switch between edit and preview, and cannot reset preview mode when the long-form session collapses or closes.
 * - Excludes: Compose sheet rendering, keyboard controller internals, and markdown renderer output.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoEditorDisplayModePolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including preview transitions keep editor session consistent
 * and reset on collapse or close.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoEditorDisplayModePolicyTest : AppFunSpec() {
    init {
        test("preview transitions keep editor session consistent and reset on collapse or close") {
            val controller = MemoEditorController()

            controller.openForCreate("draft")
            val initialFocusRequestToken = controller.focusRequestToken

            (controller.displayMode) shouldBe (InputEditorDisplayMode.Edit)

            controller.setExpanded(true)
            controller.updateDisplayMode(InputEditorDisplayMode.Preview)

            (controller.displayMode) shouldBe (InputEditorDisplayMode.Preview)
            (controller.focusRequestToken) shouldBe (initialFocusRequestToken)

            controller.updateDisplayMode(InputEditorDisplayMode.Edit)

            (controller.displayMode) shouldBe (InputEditorDisplayMode.Edit)
            (controller.focusRequestToken) shouldBe (initialFocusRequestToken + 1L)

            controller.updateDisplayMode(InputEditorDisplayMode.Preview)
            controller.setExpanded(false)
            (controller.displayMode) shouldBe (InputEditorDisplayMode.Edit)

            controller.setExpanded(true)
            controller.updateDisplayMode(InputEditorDisplayMode.Preview)
            controller.close()
            (controller.displayMode) shouldBe (InputEditorDisplayMode.Edit)
        }
    }
}
