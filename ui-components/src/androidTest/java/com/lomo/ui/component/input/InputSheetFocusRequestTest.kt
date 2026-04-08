package com.lomo.ui.component.input

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet explicit focus-request handling.
 * - Behavior focus: an already-visible input sheet must re-focus its editor and reactivate IME when the hosting layer emits a new focus request token.
 * - Observable outcomes: EditText focus state and InputMethodManager active-editor state after the token changes.
 * - Red phase: Fails before the fix because InputSheet has no explicit focus-request contract, so a host cannot re-trigger focus or IME once the sheet is already visible.
 * - Excludes: memo submission logic, host-layer controller policy, and OEM keyboard visuals.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetFocusRequestTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusRequestToken_refocusesAnAlreadyVisibleEditor() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))
        val focusRequestToken = mutableLongStateOf(1L)
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state =
                        InputSheetState(
                            inputValue = inputValue.value,
                            focusRequestToken = focusRequestToken.longValue,
                        ),
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
        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            editor.clearFocus()
            inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
        }
        waitForEditorToLoseFocus(editor)

        composeRule.runOnUiThread {
            focusRequestToken.longValue += 1L
        }

        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            assertTrue(editor.hasFocus())
            assertTrue(inputMethodManager.isActive(editor))
        }
    }

    @Test
    fun removingTheSheet_detachesTheEditorAndDeactivatesIme() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))
        val focusRequestToken = mutableLongStateOf(1L)
        val showSheet = mutableStateOf(true)
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                if (showSheet.value) {
                    InputSheet(
                        state =
                            InputSheetState(
                                inputValue = inputValue.value,
                                focusRequestToken = focusRequestToken.longValue,
                            ),
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
        }

        val editor = waitForEditor()
        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            showSheet.value = false
        }

        composeRule.waitUntil(5_000) {
            var editorDetachedAndInactive = false
            composeRule.runOnUiThread {
                editorDetachedAndInactive = !editor.isAttachedToWindow && !inputMethodManager.isActive(editor)
            }
            editorDetachedAndInactive
        }

        composeRule.runOnUiThread {
            assertTrue(!editor.isAttachedToWindow)
            assertTrue(!inputMethodManager.isActive(editor))
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

    private fun waitForEditorToLoseFocus(
        editor: EditText,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            var lostFocus = false
            composeRule.runOnUiThread {
                lostFocus = !editor.hasFocus()
            }
            lostFocus
        }
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
