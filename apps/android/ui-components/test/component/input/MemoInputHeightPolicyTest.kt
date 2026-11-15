package com.lomo.ui.component.input

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given explicit line height and a minimum line count, when editor height is calculated, then the resulting
 *   pixel height follows that line height.
 * - Given unspecified line height, when height is calculated, then the text font size supplies the declared
 *   fallback.
 * - Unit under test: memo input minimum-height policy.
 * - Capability: the empty editor and first-character editor must share the same minimum content height so the input container does not jump when text becomes non-empty.
 * - Observable outcomes: deterministic minimum-height pixel calculations from the editor text style.
 * - TDD proof: Fails before the fix because the input editor has no shared minimum-height policy, so empty and non-empty states can measure from different widget paths.
 * - Excludes: IME animation, Compose sheet transitions, and OEM EditText rendering quirks.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoInputHeightPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including minimum content height uses explicit line height
 * across the configured minimum lines; minimum content height falls back to font size when line height is
 * unspecified.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoInputHeightPolicyTest : UiComponentsFunSpec() {
    init {
        test("minimum content height uses explicit line height across the configured minimum lines") {
            val minHeight =
                resolveMemoInputMinimumContentHeightPx(
                    style = TextStyle(fontSize = 14.sp, lineHeight = 16.sp),
                    density = Density(density = 1f),
                )

            (minHeight) shouldBe (48)
        }
    }

    init {
        test("minimum content height falls back to font size when line height is unspecified") {
            val minHeight =
                resolveMemoInputMinimumContentHeightPx(
                    style = TextStyle(fontSize = 18.sp),
                    density = Density(density = 1f),
                )

            (minHeight) shouldBe (54)
        }
    }
}
