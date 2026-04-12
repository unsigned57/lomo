package com.lomo.app.feature.memo

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoEditorSheetHost editor focus recovery for an already-visible create session.
 * - Behavior focus: opening the editor should auto-focus the input, and an explicit ensureVisible request must re-focus and reactivate IME after the editor loses focus.
 * - Observable outcomes: EditText focus state and InputMethodManager active-editor state after open and ensureVisible.
 * - Red phase: Fails before the fix because ensureVisible() only rewrites the same visible flag and does not trigger a fresh focus or IME request once the sheet is already visible.
 * - Excludes: memo persistence, submit flow, dismissal flow, media pickers, and OEM keyboard visuals.
 */
@RunWith(AndroidJUnit4::class)
class MemoEditorImeRequestTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openForCreate_autoFocusesTheEditorAndActivatesIme() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _ -> },
                )
            }
        }

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            controller.openForCreate("draft")
        }

        val editor = waitForEditor()
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            assertTrue(editor.hasFocus())
            assertTrue(inputMethodManager.isActive(editor))
        }
    }

    @Test
    fun ensureVisible_refocusesTheExistingEditorSession() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _ -> },
                )
            }
        }

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            controller.openForCreate("draft")
        }

        val editor = waitForEditor()
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            editor.clearFocus()
            inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
        }
        waitForEditorToLoseFocus(editor)

        composeRule.runOnUiThread {
            controller.ensureVisible()
        }

        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            assertTrue(editor.hasFocus())
            assertTrue(inputMethodManager.isActive(editor))
        }
    }

    @Test
    fun close_detachesTheEditorAndDeactivatesIme() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _ -> },
                )
            }
        }

        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            controller.openForCreate("draft")
        }

        val editor = waitForEditor()
        waitForFocusAndIme(editor = editor, inputMethodManager = inputMethodManager)

        composeRule.runOnUiThread {
            controller.close()
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
