package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet display-mode bar contract.
 * - Behavior focus: expanded long-form mode should keep the edit/preview switch and collapse
 *   affordance, but should not render a separate "long-form" title label.
 * - Observable outcomes: InputSheet display-mode bar source keeps edit/preview labels and no
 *   longer reads the long-form title string resource.
 * - Red phase: Fails before the fix because InputEditorDisplayModeBar still renders
 *   `R.string.input_long_form_mode` above the edit/preview pills.
 * - Excludes: Compose layout metrics, animation timing, and instrumentation rendering.
 */
class InputSheetDisplayModeBarContractTest {
    private val sourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()

    @Test
    fun `display mode bar keeps mode pills without long form title`() {
        assertTrue(
            "Expanded long-form mode should still expose the edit pill.",
            sourceText.contains("R.string.input_mode_edit"),
        )
        assertTrue(
            "Expanded long-form mode should still expose the preview pill.",
            sourceText.contains("R.string.input_mode_preview"),
        )
        assertFalse(
            "The display-mode bar should not render the long-form title string any more.",
            sourceText.contains("R.string.input_long_form_mode"),
        )
    }
}
