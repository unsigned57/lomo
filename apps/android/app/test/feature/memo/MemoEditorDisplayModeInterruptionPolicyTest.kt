package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputEditorDisplayMode
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a preview session, when it returns to edit and immediately collapses, then the focus token advances
 *   once and the session returns to compact edit.
 * - Unit under test: MemoEditorController preview interruption policy.
 * - Capability: returning from preview to edit should request focus once, and collapsing the
 *   long-form session immediately afterwards must not issue a second focus request while resetting
 *   the display mode back to edit.
 * - Observable outcomes: focusRequestToken progression and final displayMode after preview -> edit
 *   -> collapse sequencing.
 * - TDD proof: Fails before the fix because preview/display-mode coordination is incomplete, so
 *   collapse handling can only be inferred from expanded mode and may retrigger focus acquisition
 *   instead of cleanly resetting to compact edit.
 * - Excludes: Compose sheet rendering, IME activation timing, and markdown preview rendering.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoEditorDisplayModeInterruptionPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including preview return followed by collapse keeps a single
 * focus request.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoEditorDisplayModeInterruptionPolicyTest : AppFunSpec() {
    init {
        test("preview return followed by collapse keeps a single focus request") {
            val controller = MemoEditorController()

            controller.openForCreate("draft")
            controller.setExpanded(true)
            controller.updateDisplayMode(InputEditorDisplayMode.Preview)

            val previewToken = controller.focusRequestToken

            controller.updateDisplayMode(InputEditorDisplayMode.Edit)
            controller.setExpanded(false)

            (controller.displayMode) shouldBe (InputEditorDisplayMode.Edit)
            (controller.mode) shouldBe (MemoEditorMode.Compact)
            (controller.focusRequestToken) shouldBe (previewToken + 1L)
        }
    }
}
