package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet editor container clipping policy.
 * - Behavior focus: the editor container must keep its rounded background without clipping child
 *   content so platform cursor and selection handles can extend outside the AndroidView bounds.
 * - Observable outcomes: InputEditorTextField source uses a shaped background for the editor
 *   container and does not apply clip(AppShapes.Large) to that container.
 * - Red phase: Fails before the fix because the editor container clips its children, which hides
 *   native text-selection handles on device.
 * - Excludes: runtime gesture dispatch, handle tint, and screenshot-level pixel validation.
 *
 * Test Change Justification:
 * - Reason category: pure refactor preserved behavior.
 * - Old behavior/assertion being replaced: the test read InputSheetComponents.kt directly for the
 *   editor container shape and clipping policy.
 * - Why old assertion is no longer correct: the editor container implementation was extracted into
 *   InputEditorTextField.kt without changing the overflow contract being asserted.
 * - Coverage preserved by: the test still checks for the same shaped background and the absence of
 *   clipping on the concrete editor container implementation.
 * - Why this is not fitting the test to the implementation: the protected behavior is unchanged;
 *   only the source location of that behavior moved during a mechanical split.
 */
class InputEditorHandleOverflowPolicyTest {
    @Test
    fun inputEditorContainer_doesNotClipHandleOverflow() {
        val sourceFile =
            File(
                "src/main/java/com/lomo/ui/component/input/InputEditorTextField.kt",
            )
        val sourceText = sourceFile.readText()

        assertFalse(
            "Input editor container must not clip rounded corners because native selection handles need to overflow the AndroidView bounds.",
            sourceText.contains(".clip(AppShapes.Large)\n                .benchmarkAnchor(benchmarkEditorTag)"),
        )
        assertTrue(
            "Input editor container should keep its rounded visual treatment through a shaped background instead of clipping children.",
            sourceText.contains(".background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapes.Large)") ||
                sourceText.contains(
                    ".background(\n                    color = MaterialTheme.colorScheme.surfaceContainerHigh,\n                    shape = AppShapes.Large,\n                )",
                ),
        )
    }
}
