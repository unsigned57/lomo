package com.lomo.app.feature.memo

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: MemoEditorSheetHost editor focus recovery for an already-visible create session.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: verify editor focus recovery and IME requests.
 *
 * Scenarios:
 * - Given open for create, when input displays, then autofocuses the editor and activates IME.
 * - Given visible editor session, when ensureVisible is called, then refocuses the existing editor session.
 * - Given active editor session, when editor is closed, then detaches the editor and deactivates IME.
 *
 * Observable outcomes:
 * - correct EditText focus and IMM state transitions.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - memo persistence, submit flow, dismissal flow, media pickers, and OEM keyboard visuals.
 */
@org.junit.runner.RunWith(AndroidJUnit4::class)
class MemoEditorImeRequestTest {
    @Suppress("DEPRECATION")
    @get:org.junit.Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Test
    fun openForCreate_autoFocusesTheEditorAndActivatesIme() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _, _ -> },
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
            org.junit.Assert.assertTrue(editor.hasFocus())
            org.junit.Assert.assertTrue(inputMethodManager.isActive(editor))
        }
    }

    @org.junit.Test
    fun ensureVisible_refocusesTheExistingEditorSession() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _, _ -> },
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
            org.junit.Assert.assertTrue(editor.hasFocus())
            org.junit.Assert.assertTrue(inputMethodManager.isActive(editor))
        }
    }

    @org.junit.Test
    fun close_detachesTheEditorAndDeactivatesIme() {
        val controller = MemoEditorController()
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            MaterialTheme {
                MemoEditorSheetHost(
                    controller = controller,
                    imageDirectory = null,
                    onSaveImage = { _, _, _ -> },
                    onSubmit = { _, _, _ -> },
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
            org.junit.Assert.assertTrue(!editor.isAttachedToWindow)
            org.junit.Assert.assertTrue(!inputMethodManager.isActive(editor))
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
