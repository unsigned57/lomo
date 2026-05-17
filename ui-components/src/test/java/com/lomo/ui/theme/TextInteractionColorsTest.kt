package com.lomo.ui.theme

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Test Contract:
 * - Unit under test: Material 3 text interaction color helpers in the ui-components layer.
 * - Behavior focus: text selection highlight, handles, and cursor-adjacent platform colors must
 *   derive from a single Material 3 primary tone while keeping the selection background visibly
 *   lighter than the handle color.
 * - Observable outcomes: resolved Compose selection colors and platform selection highlight color.
 * - Red phase: Fails before the fix because the helpers do not yet provide the requested lighter
 *   selection background and shared primary-toned handle color.
 * - Excludes: Android widget tint application, OEM drawable behavior, and full theme composition.
 *
 * Test Change Justification:
 * - Reason category: pure refactor preserved behavior.
 * - Replaced assertion reference: `MemoTextSelectionBackgroundAlpha`.
 * - Previous assertion is no longer correct because the production helper constant was renamed to
 *   satisfy repository naming rules without changing the resolved alpha value.
 * - Retained coverage: both tests still assert the exact same primary-based highlight alpha in
 *   Compose selection colors and platform selection colors.
 * - This is not changing the test to fit the implementation because the observable behavior and
 *   expected alpha remain identical; only the referenced symbol name changed.
 */
class TextInteractionColorsTest : UiComponentsFunSpec() {
    private val primary = Color(0xFF1E88E5)
    private val colorScheme = lightColorScheme(primary = primary)

    init {
        test("compose text selection colors use primary handle and lighter background") {
        val selectionColors = memoTextSelectionColors(colorScheme)

        (selectionColors.handleColor) shouldBe (primary)
        (selectionColors.backgroundColor) shouldBe (primary.copy(alpha = memoTextSelectionBackgroundAlpha))
        }
    }

    init {
        test("platform selection colors reuse the same primary family") {
        (memoPlatformTextHandleColor(colorScheme)) shouldBe (primary)
        (memoPlatformTextSelectionHighlightColor(colorScheme)) shouldBe (primary.copy(alpha = memoTextSelectionBackgroundAlpha))
        }
    }
}
