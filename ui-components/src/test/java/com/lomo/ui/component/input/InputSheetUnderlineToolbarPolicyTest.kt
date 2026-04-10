package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet underline toolbar exposure and callback wiring.
 * - Behavior focus: the editor toolbar must expose an underline tool button and thread the action from InputSheetContent through the editor panel into the scrollable toolbar tools.
 * - Observable outcomes: source declares an underline tool id, uses the underlined format icon and content description string, and passes an underline insertion callback through the input sheet layers.
 * - Red phase: Fails before the fix because the toolbar exposes no underline button and no underline insertion callback is wired through the input sheet pipeline.
 * - Excludes: runtime Compose semantics, icon rendering pixels, and markdown renderer output.
 */
class InputSheetUnderlineToolbarPolicyTest {
    private val contentSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetContent.kt").readText()
    private val componentsSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()
    private val toolbarSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputEditorToolbarComponents.kt").readText()

    @Test
    fun underlineAction_isWiredFromContentIntoEditorToolbar() {
        assertTrue(
            "InputSheetContent should build an underline insertion value from the current TextFieldValue.",
            contentSourceText.contains(
                "onInsertUnderline = { callbacks.onInputValueChange(buildUnderlineInsertionValue(inputValue)) }",
            ),
        )
        assertTrue(
            "InputEditorPanel should accept an underline insertion callback so the toolbar can trigger formatting.",
            componentsSourceText.contains("onInsertUnderline: () -> Unit,"),
        )
        assertTrue(
            "InputEditorToolbar should receive the underline insertion callback from the panel.",
            componentsSourceText.contains("onInsertUnderline = onInsertUnderline,"),
        )
        assertTrue(
            "Scrollable toolbar tools should receive the underline insertion callback from the toolbar.",
            componentsSourceText.contains("onInsertUnderline: () -> Unit,"),
        )
    }

    @Test
    fun underlineTool_isExposedInScrollableToolbar() {
        assertTrue(
            "Toolbar tool ids should include underline so formatting is available alongside todo/tag actions.",
            toolbarSourceText.contains("\"underline\""),
        )
        assertTrue(
            "Underline tool should use the Material underlined-format icon for affordance consistency.",
            toolbarSourceText.contains("Icons.Rounded.FormatUnderlined"),
        )
        assertTrue(
            "Underline tool should expose a dedicated accessibility/content-description string.",
            toolbarSourceText.contains("R.string.cd_add_underline"),
        )
    }
}
