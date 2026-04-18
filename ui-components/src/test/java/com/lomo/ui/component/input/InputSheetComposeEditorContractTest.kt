package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Compose-native input editor contract for InputSheet.
 * - Behavior focus: the memo input surface must be implemented with Compose text input instead of an Android EditText bridge, while the InputSheet orchestration continues to own focus and submission flow.
 * - Observable outcomes: InputEditorTextField source uses a Compose text input primitive, no longer hosts AndroidView, and InputSheet/InputSheetContent no longer track a MemoInputEditText bridge instance.
 * - Red phase: Fails before the fix because InputEditorTextField still wraps MemoInputEditText inside AndroidView and InputSheet state/effects still depend on an editorView bridge object.
 * - Excludes: pixel rendering, IME OEM quirks, and memo persistence logic.
 */
class InputSheetComposeEditorContractTest {
    private val inputEditorSource =
        File("src/main/java/com/lomo/ui/component/input/InputEditorTextField.kt").readText()
    private val inputSheetSource =
        File("src/main/java/com/lomo/ui/component/input/InputSheet.kt").readText()
    private val inputSheetContentSource =
        File("src/main/java/com/lomo/ui/component/input/InputSheetContent.kt").readText()

    @Test
    fun `input editor uses compose text input instead of android view bridge`() {
        assertTrue(
            "InputEditorTextField should use a Compose text input primitive after the editor migration.",
            inputEditorSource.contains("BasicTextField(") || inputEditorSource.contains("TextField("),
        )
        assertFalse(
            "AndroidView keeps the editor tied to the platform EditText bridge and defeats the Compose-native migration.",
            inputEditorSource.contains("AndroidView("),
        )
        assertFalse(
            "The Compose-native editor should not keep the MemoInputEditText bridge alive.",
            inputEditorSource.contains("MemoInputEditText"),
        )
    }

    @Test
    fun `input sheet orchestration no longer tracks editor bridge instances`() {
        assertFalse(
            "InputSheet should not store an editorView bridge after moving to Compose-native text input.",
            inputSheetSource.contains("editorView"),
        )
        assertFalse(
            "InputSheetContent should not pass bridge callbacks once the editor is Compose-native.",
            inputSheetContentSource.contains("onEditorReady"),
        )
        assertFalse(
            "InputSheetContent should not mention the MemoInputEditText bridge after the migration.",
            inputSheetContentSource.contains("MemoInputEditText"),
        )
    }
}
