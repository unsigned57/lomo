package com.lomo.ui.component.input

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input editable-selection bridge contract.
 * - Behavior focus: the memo editor bridge must not force either selectable-only mode or an explicit non-selectable mode, because the input sheet relies on the platform EditText default editable-selection behavior and stable native range handles.
 * - Observable outcomes: source-level absence of direct selectable-mode calls in MemoInputEditTextBridge.kt and continued installation of an editable movement method.
 * - Red phase: Fails before the fix because the bridge explicitly calls setTextIsSelectable(true) or setTextIsSelectable(false), both of which can disrupt native editable range-handle behavior on device.
 * - Excludes: Android widget internals, OEM handle visuals, IME rendering, and Compose hosting.
 */
/*
 * Test Change Justification:
 * - Reason category: factual correction.
 * - Exact behavior or assertion being replaced: the old contract required exactly one setTextIsSelectable(false) call during creation.
 * - Why the previous assertion is no longer correct: device-level regression proof on Samsung/Android 16 showed that even forcing false once can suppress the expected editable long-press range handles, so the correct contract is to avoid explicit selectable-state writes entirely and preserve the platform default EditText mode.
 * - What retained or new coverage preserves the original risk: the updated source contract still forbids selectable-only mode and now also forbids explicit non-selectable mode, while the device regression test verifies that long-press can produce a non-collapsed selection on a focused editor.
 * - Why this is not "changing the test to fit the implementation": the change follows a reproduced device failure and tightens the behavior contract around the user-visible selection regression instead of weakening it.
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
    fun `memo input bridge still installs an editable movement method`() {
        assertTrue(
            sourceText.contains("movementMethod = ArrowKeyMovementMethod.getInstance()"),
        )
    }
}
