package com.lomo.ui.component.input

import android.text.Editable
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.getSystemService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet editor buffer continuity during no-op recomposition.
 * - Behavior focus: repeated delete/backspace through one active InputConnection or a long-press delete command must keep deleting across recompositions, and no-op recomposition must preserve the active buffer state.
 * - Observable outcomes: repeated delete commands shrink the visible text on every call, long-press delete removes more than one character, and unrelated state changes leave the current text buffer intact.
 * - Red phase: Intended to fail before the fix when an IME path is sensitive to content replacement during recomposition; current emulator coverage stays green, so companion JVM policy tests lock the replacement decision directly.
 * - Excludes: OEM keyboard visual behavior, memo submission flow, and persistence behavior.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetEditorBufferContinuityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stylingOnlyRecomposition_keepsTheActiveEditableInstance() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))
        val revision = mutableIntStateOf(0)

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state =
                        InputSheetState(
                            inputValue = inputValue.value,
                            hints = listOf("revision:${revision.intValue}"),
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
        lateinit var initialEditable: Editable

        composeRule.runOnUiThread {
            initialEditable = editor.text
            revision.intValue += 1
        }
        composeRule.waitForIdle()

        lateinit var updatedEditable: Editable
        composeRule.runOnUiThread {
            updatedEditable = editor.text
        }

        assertSame(initialEditable, updatedEditable)
        assertEquals("abcdef", updatedEditable.toString())
    }

    @Test
    fun repeatedDeleteThroughSameInputConnection_keepsDeleting() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))

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

        repeat(3) {
            composeRule.runOnUiThread {
                inputConnection.deleteSurroundingText(1, 0)
            }
            composeRule.waitForIdle()
        }

        lateinit var remainingText: String
        composeRule.runOnUiThread {
            remainingText = editor.text.toString()
        }

        assertEquals("abc", remainingText)
    }

    @Test
    fun longPressDelete_removesMoreThanOneCharacter() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))

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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.setSelection(editor.text.length)
        }
        composeRule.waitForIdle()

        device.executeShellCommand("input keyevent --longpress KEYCODE_DEL")
        composeRule.waitForIdle()

        lateinit var remainingText: String
        composeRule.runOnUiThread {
            remainingText = editor.text.toString()
        }

        assertTrue(remainingText.length < 5)
    }

    @Test
    fun softKeyboardDeleteLongPress_removesMoreThanOneCharacter() {
        val inputValue = mutableStateOf(TextFieldValue("abcdef", TextRange(6)))

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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val inputMethodManager = checkNotNull(composeRule.activity.getSystemService<InputMethodManager>())

        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.setSelection(editor.text.length)
            inputMethodManager.showSoftInput(editor, 0)
        }
        composeRule.waitForIdle()

        assertTrue(device.wait(Until.hasObject(By.descContains("Delete")), 5_000))
        val deleteKey = device.findObject(By.descContains("Delete"))
        assertNotNull(deleteKey)

        deleteKey!!.longClick()
        device.waitForIdle()

        lateinit var remainingText: String
        composeRule.runOnUiThread {
            remainingText = editor.text.toString()
        }

        assertTrue(remainingText.length < 5)
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
