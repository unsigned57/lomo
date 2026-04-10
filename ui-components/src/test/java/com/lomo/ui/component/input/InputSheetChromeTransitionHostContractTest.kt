package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet long-form chrome transition host contract.
 * - Behavior focus: edit/preview switching in long-form mode must keep stable animation hosts for
 *   the display-mode bar and formatting toolbar instead of conditionally inserting or removing
 *   those chrome blocks from the composable tree.
 * - Observable outcomes: InputSheetComponents source declares a dedicated chrome transition host
 *   helper and no longer gates the display-mode bar or formatting toolbar behind raw
 *   `if (chromeState...)` conditionals.
 * - Red phase: Fails before the fix because InputEditorPanel still uses bare `if
 *   (chromeState.showsDisplayModeToggle)` and `if (chromeState.showsFormattingToolbar)` branches,
 *   which directly mount and unmount long-form chrome during mode changes.
 * - Excludes: Compose runtime frame timing, markdown rendering output, and IME/device variance.
 */
class InputSheetChromeTransitionHostContractTest {
    private val sourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()

    @Test
    fun `long form chrome uses stable transition host instead of raw conditional branches`() {
        assertTrue(
            "InputSheetComponents should expose a dedicated transition host helper for long-form chrome.",
            sourceText.contains("private fun InputEditorChromeTransitionHost("),
        )
        assertFalse(
            "Display-mode bar should not be directly inserted or removed with a raw if branch.",
            sourceText.contains("if (chromeState.showsDisplayModeToggle) {"),
        )
        assertFalse(
            "Formatting toolbar should not be directly inserted or removed with a raw if branch.",
            sourceText.contains("if (chromeState.showsFormattingToolbar) {"),
        )
    }
}
