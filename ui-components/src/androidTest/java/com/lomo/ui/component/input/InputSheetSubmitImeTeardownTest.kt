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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet submit-time focus and IME teardown.
 * - Behavior focus: tapping send must deactivate IME and release editor focus as soon as submit starts, even if the host has not removed the sheet yet.
 * - Observable outcomes: onSubmit is called, the editor stays attached during observation, and InputMethodManager is no longer active for that editor while it has no focus.
 * - Red phase: Fails before the fix because the submit path invokes onSubmit directly without first tearing down editor focus or IME.
 * - Excludes: memo persistence, host-layer close timing, and OEM-specific keyboard visuals.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetSubmitImeTeardownTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun submitButton_releasesFocusAndImeBeforeHostRemoval() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))
        val submitCount = mutableIntStateOf(0)
        lateinit var inputMethodManager: InputMethodManager
        lateinit var sendDescription: String

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state = InputSheetState(inputValue = inputValue.value),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onSubmit = { submitCount.intValue += 1 },
                            onImageClick = {},
                        ),
                )
            }
        }

        val editor = waitForEditor()
        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            sendDescription = composeRule.activity.getString(R.string.cd_send)
        }
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.onNodeWithContentDescription(sendDescription).performClick()

        composeRule.waitUntil(5_000) {
            var submitStateObserved = false
            composeRule.runOnUiThread {
                submitStateObserved =
                    submitCount.intValue == 1 &&
                        editor.isAttachedToWindow &&
                        !editor.hasFocus() &&
                        !inputMethodManager.isActive(editor)
            }
            submitStateObserved
        }

        composeRule.runOnUiThread {
            assertEquals(1, submitCount.intValue)
            assertTrue(editor.isAttachedToWindow)
            assertFalse(editor.hasFocus())
            assertFalse(inputMethodManager.isActive(editor))
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
