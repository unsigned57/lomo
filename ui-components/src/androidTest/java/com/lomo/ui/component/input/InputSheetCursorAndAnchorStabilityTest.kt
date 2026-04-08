package com.lomo.ui.component.input

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet cursor visibility and first-character anchor stability.
 * - Behavior focus: a focused editor must keep a visible cursor drawable, and entering the first character must not shift the editor upward unexpectedly.
 * - Observable outcomes: non-null cursor drawable on supported API levels, stable focused editor geometry after the first committed character, stable editor height after the first committed character, and preserved end selection.
 * - Red phase: Fails before the fix because the EditText bridge clears the cursor drawable on API 29+ and the first committed character can remeasure the editor unexpectedly in the sheet.
 * - Excludes: OEM keyboard artwork, detailed animation timing, and memo submission side effects.
 */
/*
 * Test Change Justification:
 * - Reason category: factual correction plus nondeterministic setup correction and companion regression scenario.
 * - Old behavior/assertion being replaced: the original anchor test sampled global screen Y while the sheet/IME stack could still be settling, and nullable cursor drawable access is also corrected to safe non-null extraction.
 * - Why old assertion is no longer correct: the previous setup could capture unrelated bottom-sheet or IME translation instead of the editor's own first-character layout stability, and direct nullable dereference is a compile-time error rather than a behavior contract.
 * - Coverage preserved by: the updated regression checks local editor geometry stability after focus and IME are active, while the companion height-stability test preserves the reported visual risk boundary.
 * - Why this is not fitting the test to the implementation: the corrected setup removes unrelated motion noise but still checks the same user-visible requirement after the first committed character.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetCursorAndAnchorStabilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusedEditor_keepsVisibleCursorDrawable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val inputValue = mutableStateOf(TextFieldValue("", TextRange.Zero))

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertTrue(editor.hasFocus())
            assertTrue(editor.isCursorVisible)
            val cursorDrawable = checkNotNull(editor.textCursorDrawable)
            assertNotNull(cursorDrawable)
            assertTrue(cursorDrawable.intrinsicWidth > 0)
            assertTrue(cursorDrawable.intrinsicHeight > 1)
        }
    }

    @Test
    fun firstCommittedCharacter_keepsEditorAnchoredInPlace() {
        val inputValue = mutableStateOf(TextFieldValue("", TextRange.Zero))
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        lateinit var inputConnection: InputConnection
        var beforeTop = 0

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            inputConnection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            beforeTop = editor.top
        }

        composeRule.runOnUiThread {
            inputConnection.commitText("a", 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            val afterTop = editor.top
            assertTrue(
                "Expected the first committed character to keep the editor anchored, but top moved from $beforeTop to $afterTop",
                kotlin.math.abs(afterTop - beforeTop) <= 1,
            )
            assertTrue(editor.hasFocus())
            assertTrue(editor.selectionStart == 1 && editor.selectionEnd == 1)
        }
    }

    @Test
    fun firstCommittedCharacter_keepsEditorHeightStable() {
        val inputValue = mutableStateOf(TextFieldValue("", TextRange.Zero))
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        lateinit var inputConnection: InputConnection
        var beforeHeight = 0

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            inputConnection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            beforeHeight = editor.height
        }

        composeRule.runOnUiThread {
            inputConnection.commitText("a", 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertTrue(
                "Expected the first committed character to keep the editor height stable, but height changed from $beforeHeight to ${editor.height}",
                kotlin.math.abs(editor.height - beforeHeight) <= 1,
            )
            assertTrue(editor.hasFocus())
            assertTrue(editor.selectionStart == 1 && editor.selectionEnd == 1)
        }
    }

    @Test
    fun firstCommittedCjkCharacter_keepsEditorAnchoredInPlace() {
        val inputValue = mutableStateOf(TextFieldValue("", TextRange.Zero))
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        lateinit var inputConnection: InputConnection
        var beforeTop = 0
        var beforeHeight = 0

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            inputConnection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            beforeTop = editor.top
            beforeHeight = editor.height
        }

        composeRule.runOnUiThread {
            inputConnection.commitText("你", 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            val afterTop = editor.top
            val afterHeight = editor.height
            assertTrue(
                "Expected the first committed CJK character to keep the editor anchored, but top moved from $beforeTop to $afterTop",
                kotlin.math.abs(afterTop - beforeTop) <= 1,
            )
            assertTrue(
                "Expected the first committed CJK character to keep the editor height stable, but height changed from $beforeHeight to $afterHeight",
                kotlin.math.abs(afterHeight - beforeHeight) <= 1,
            )
            assertTrue(editor.hasFocus())
            assertTrue(editor.selectionStart == 1 && editor.selectionEnd == 1)
        }
    }

    @Test
    fun firstCommittedCharacter_keepsVisibleCursorDrawable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val inputValue = mutableStateOf(TextFieldValue("", TextRange.Zero))

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        lateinit var inputConnection: InputConnection

        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.setSelection(editor.text.length)
            inputConnection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            inputConnection.commitText("a", 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertTrue(editor.hasFocus())
            assertTrue(editor.isCursorVisible)
            val cursorDrawable = checkNotNull(editor.textCursorDrawable)
            assertNotNull(cursorDrawable)
            assertTrue(cursorDrawable.intrinsicWidth > 0)
            assertTrue(cursorDrawable.intrinsicHeight > 1)
            assertTrue(editor.selectionStart == 1 && editor.selectionEnd == 1)
        }
    }

    private fun waitForEditor(timeoutMillis: Long = 5_000): EditText {
        var editor: EditText? = null
        composeRule.waitUntil(timeoutMillis) {
            composeRule.runOnUiThread {
                editor = findFirstEditText(composeRule.activity.findViewById(android.R.id.content))
            }
            editor != null
        }
        return checkNotNull(editor)
    }

    private fun waitForFocusAndIme(
        editor: EditText,
        inputMethodManager: InputMethodManager,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            var isReady = false
            composeRule.runOnUiThread {
                isReady = editor.hasFocus() && inputMethodManager.isActive(editor)
            }
            isReady
        }
    }

    private fun findFirstEditText(root: View): EditText? {
        if (root is EditText) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findFirstEditText(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
