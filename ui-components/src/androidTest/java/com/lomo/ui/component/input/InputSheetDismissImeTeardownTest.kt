package com.lomo.ui.component.input

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet dismiss-time focus and IME teardown.
 * - Behavior focus: once a dismiss request starts, the still-attached editor must release focus and deactivate IME before the host removes the sheet after the exit animation.
 * - Observable outcomes: EditText remains attached during the dismiss window, loses focus before host removal, and InputMethodManager is no longer active for that editor.
 * - Red phase: Fails before the fix because InputSheet only asks the keyboard controller to hide during dismiss; the editor can remain focused and keep IME active until detach.
 * - Excludes: host screen removal policy, animation visuals, draft/submission logic, and OEM-specific keyboard appearance.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetDismissImeTeardownTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dismissRequest_releasesFocusAndImeBeforeHostRemoval() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))
        val showSheet = mutableStateOf(true)
        val dismissCount = mutableIntStateOf(0)
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                if (showSheet.value) {
                    InputSheet(
                        state = InputSheetState(inputValue = inputValue.value),
                        callbacks =
                            InputSheetCallbacks(
                                onInputValueChange = { inputValue.value = it },
                                onDismiss = {
                                    dismissCount.intValue += 1
                                    showSheet.value = false
                                },
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
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.waitUntil(5_000) {
            var dismissStateObserved = false
            composeRule.runOnUiThread {
                dismissStateObserved =
                    editor.isAttachedToWindow &&
                        !editor.hasFocus() &&
                        !inputMethodManager.isActive(editor) &&
                        dismissCount.intValue == 0
            }
            dismissStateObserved
        }

        composeRule.runOnUiThread {
            assertTrue(editor.isAttachedToWindow)
            assertFalse(editor.hasFocus())
            assertFalse(inputMethodManager.isActive(editor))
            assertEquals(0, dismissCount.intValue)
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
