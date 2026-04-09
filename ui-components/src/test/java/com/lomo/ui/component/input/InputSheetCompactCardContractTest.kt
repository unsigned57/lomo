package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet compact-card layout contract.
 * - Behavior focus: the default short-form editor must behave as a compact floating card that reuses
 *   one unified editor skeleton with conditional modifiers, instead of switching to a separate
 *   composable tree that causes a one-frame flash on expand/collapse.
 * - Observable outcomes: InputEditorPanel uses one layout skeleton with conditional Modifier branches
 *   for compact and expanded sizing, and compact surface sizing no longer falls back to a
 *   screen-height ratio placeholder.
 * - Red phase: Fails before the fix because the panel still switches between two separate composable
 *   trees (InputEditorCompactLayout / InputEditorExpandedLayout), causing the AndroidView to be
 *   destroyed and recreated on each mode change.
 * - Excludes: pixel-perfect animation curves, OEM keyboard behavior, and memo submission logic.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Exact behavior or assertion being replaced: previously asserted that separate
 *   InputEditorCompactLayout and InputEditorExpandedLayout private functions existed.
 * - Why the previous assertion is no longer correct: the two-layout approach was identified as the
 *   root cause of expand/collapse flicker and toolbar position jumping. The new architecture uses
 *   one unified skeleton with conditional Modifier branches.
 * - What retained or new coverage preserves the original risk: the test now verifies that the
 *   unified skeleton uses conditional Modifier branches (fillMaxHeight / weight) instead of two
 *   separate composable trees, which ensures compact stays card-like without the flicker.
 * - Why this is not "changing the test to fit the implementation": the previous approach was proven
 *   defective (one-frame flash on transitions), and the new test asserts the architectural property
 *   that fixes the user-visible bug.
 */
class InputSheetCompactCardContractTest {
    private val sourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()

    @Test
    fun `input sheet uses one unified editor skeleton with conditional modifiers`() {
        assertTrue(
            "InputEditorPanel should use a conditional fillMaxHeight modifier to switch between compact and expanded, instead of two separate composable trees.",
            sourceText.contains(".then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)"),
        )
        assertTrue(
            "InputEditorPanel should use a conditional weight modifier on the text field for expanded mode.",
            sourceText.contains("if (isExpanded) Modifier.weight(1f) else Modifier"),
        )
    }

    @Test
    fun `compact surface sizing no longer depends on a screen-height fallback ratio`() {
        assertFalse(
            "Using a fallback screen-height ratio for compact sizing turns the short-form editor into a collapsed fullscreen sheet instead of a content-wrapped floating card.",
            sourceText.contains("INPUT_SHEET_COLLAPSE_TARGET_HEIGHT_FALLBACK_RATIO"),
        )
    }
}
