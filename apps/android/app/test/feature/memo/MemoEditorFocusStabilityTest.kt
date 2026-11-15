package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given an active editor session, when it expands and collapses, then its existing focus request token is
 *   retained.
 * - Unit under test: MemoEditorController focus-token stability during long-form mode toggles.
 * - Capability: expanding and collapsing the current editor session must not request a new editor focus cycle, because the existing keyboard session should stay intact while the sheet morphs between compact and long-form.
 * - Observable outcomes: focusRequestToken remains unchanged across expand and collapse on an already-visible session.
 * - TDD proof: Fails before the fix because expand() and collapse() both bump focusRequestToken, which re-triggers InputSheet focus effects and causes the keyboard layout to flicker during long-form transitions.
 * - Excludes: Compose rendering, Android IME OEM behavior, and memo submission flow.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoEditorFocusStability.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including expanding and collapsing current session keeps the
 * existing focus request token.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoEditorFocusStabilityTest : AppFunSpec() {
    init {
        test("expanding and collapsing current session keeps the existing focus request token") {
            val controller = MemoEditorController()

            controller.openForCreate("draft")
            val initialFocusRequestToken = controller.focusRequestToken

            controller.setExpanded(true)
            (controller.focusRequestToken) shouldBe (initialFocusRequestToken)

            controller.setExpanded(false)
            (controller.focusRequestToken) shouldBe (initialFocusRequestToken)
        }
    }
}
