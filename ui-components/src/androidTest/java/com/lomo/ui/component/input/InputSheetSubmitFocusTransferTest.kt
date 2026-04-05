package com.lomo.ui.component.input

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet submit-time focus release with sibling action buttons.
 * - Behavior focus: rapid double-enter submission must not transfer focus to an external action button while the sheet is closing.
 * - Observable outcomes: submit count increments once, the sheet host is removed, and the sibling action button never enters a focused state.
 * - Red phase: Fails before the fix because submit-time focus teardown clears the editor without parking focus on an inert sink, so the next focus candidate can become the host action button.
 * - Excludes: app-level navigation wiring, icon ripple artwork, and OEM keyboard implementation details.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetSubmitFocusTransferTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun doubleEnterSubmit_doesNotFocusSiblingActionButton() {
        val inputValue = mutableStateOf(TextFieldValue("abc", TextRange(3)))
        val showSheet = mutableStateOf(true)
        val submitCount = mutableIntStateOf(0)
        var siblingFocused by mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.onFocusChanged { siblingFocused = it.isFocused },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "menu",
                        )
                    }
                    if (showSheet.value) {
                        InputSheet(
                            state = InputSheetState(inputValue = inputValue.value),
                            callbacks =
                                InputSheetCallbacks(
                                    onInputValueChange = { inputValue.value = it },
                                    onDismiss = { showSheet.value = false },
                                    onSubmit = {
                                        submitCount.intValue += 1
                                        showSheet.value = false
                                    },
                                    onImageClick = {},
                                ),
                        )
                    }
                }
            }
        }

        val editor = waitForEditor()
        lateinit var inputConnection: InputConnection

        composeRule.runOnUiThread {
            inputConnection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        }

        composeRule.runOnUiThread {
            inputConnection.commitText("\n", 1)
            inputConnection.commitText("\n", 1)
        }
        composeRule.waitForIdle()

        composeRule.waitUntil(5_000) { !showSheet.value }

        composeRule.runOnUiThread {
            assertEquals(1, submitCount.intValue)
            assertFalse(siblingFocused)
            assertTrue(!showSheet.value)
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
