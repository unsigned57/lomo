package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input IME stability bridge contract.
 * - Behavior focus: long-form transitions must keep one stable text-editor configuration so the platform keyboard does not temporarily switch layouts while the sheet animates.
 * - Observable outcomes: MemoInputEditTextBridge configures an explicit multiline text input type and avoids triggering showSoftInput/requestFocusFromTouch from the view's focus-change callback.
 * - Red phase: Fails before the fix because the bridge leaves the input type implicit and manually re-shows the keyboard on focus changes, which can cause the IME layout to flicker during long-form transitions.
 * - Excludes: OEM keyboard rendering, Android input-method internals, and Compose layout animation.
 */
class MemoInputImeStabilityContractTest {
    private val sourceText =
        File("src/main/java/com/lomo/ui/component/input/MemoInputEditTextBridge.kt").readText()

    @Test
    fun `memo input bridge uses stable multiline text input config without refiring ime on focus change`() {
        assertTrue(
            "MemoInputEditText should declare an explicit multiline text input type so keyboard classification stays stable across layout transitions.",
            sourceText.contains("InputType.TYPE_CLASS_TEXT") &&
                sourceText.contains("InputType.TYPE_TEXT_FLAG_MULTI_LINE"),
        )
        assertFalse(
            "MemoInputEditText focus changes must not call requestFocusFromTouch(), which replays focus acquisition during long-form transitions.",
            sourceText.contains("requestFocusFromTouch()"),
        )
        assertFalse(
            "MemoInputEditText focus changes must not call showSoftInput directly, because the sheet-level focus coordinator already owns keyboard activation.",
            sourceText.contains("showSoftInput(this"),
        )
    }
}
