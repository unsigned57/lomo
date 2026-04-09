package com.lomo.ui.component.input

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input editable-selection bridge contract.
 * - Behavior focus: the memo editor bridge must not force either selectable-only mode or an explicit
 *   non-selectable mode, and must not install ArrowKeyMovementMethod which blocks touch-based
 *   selection handle dragging and cross-line selection.
 * - Observable outcomes: source-level absence of direct selectable-mode calls and
 *   ArrowKeyMovementMethod in MemoInputEditTextBridge.kt.
 * - Red phase: Fails before the fix because the bridge installs ArrowKeyMovementMethod which
 *   prevents smooth touch selection handle dragging across lines.
 * - Excludes: Android widget internals, OEM handle visuals, IME rendering, and Compose hosting.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Exact behavior or assertion being replaced: the old contract asserted that
 *   ArrowKeyMovementMethod.getInstance() must be present.
 * - Why the previous assertion is no longer correct: ArrowKeyMovementMethod was identified as the
 *   root cause of the bug where selection handle dragging could only move one character at a time
 *   and could not cross lines. ArrowKeyMovementMethod is designed for arrow-key navigation in
 *   non-editable TextViews, not for touch selection in EditText.
 * - What retained or new coverage preserves the original risk: the test now asserts that
 *   ArrowKeyMovementMethod is NOT installed, which ensures the native EditText touch selection
 *   behavior works correctly for drag selection.
 * - Why this is not "changing the test to fit the implementation": this follows a reproduced bug
 *   where ArrowKeyMovementMethod was the root cause, and the test now enforces the correct
 *   behavior contract.
 */
class MemoInputEditableSelectionModeContractTest {
    private val sourceText: String by lazy {
        Paths
            .get("src/main/java/com/lomo/ui/component/input/MemoInputEditTextBridge.kt")
            .toFile()
            .readText()
    }

    @Test
    fun `memo input bridge must not force selectable only text mode`() {
        assertFalse(
            """
            Editable memo input should not force selectable-only TextView mode.
            That mode suppresses the platform's editable selection-handle behavior.
            """.trimIndent(),
            sourceText.contains("setTextIsSelectable(true)"),
        )
    }

    @Test
    fun `memo input bridge must not force non selectable mode either`() {
        assertFalse(
            """
            Editable memo input should rely on the platform EditText default mode instead of
            explicitly forcing non-selectable text behavior.
            """.trimIndent(),
            sourceText.contains("setTextIsSelectable(false)"),
        )
    }

    @Test
    fun `memo input bridge must not install ArrowKeyMovementMethod`() {
        assertFalse(
            """
            ArrowKeyMovementMethod blocks touch-based selection handle dragging and cross-line
            selection. EditText should use its default touch selection behavior instead.
            """.trimIndent(),
            sourceText.contains("ArrowKeyMovementMethod"),
        )
    }
}
