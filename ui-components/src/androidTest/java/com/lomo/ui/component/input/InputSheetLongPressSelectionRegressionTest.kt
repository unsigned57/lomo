package com.lomo.ui.component.input

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.benchmark.BenchmarkAnchorConfig
import com.lomo.ui.benchmark.LocalBenchmarkAnchorConfig
import java.util.Collections
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet editable long-press selection behavior on a real device.
 * - Behavior focus: long-pressing editor text must reach the embedded MemoInputEditText and trigger
 *   its touch pipeline instead of being consumed by sheet-level gesture wrappers.
 * - Observable outcomes: underlying EditText MotionEvent actions after a Compose-injected long-press
 *   on the tagged editor container.
 * - Red phase: Fails before the fix when InputSheet-level gesture interception prevents the memo
 *   editor from receiving long-press touch events at all on device.
 * - Excludes: OEM handle artwork, copy toolbar strings, and exact selection-handle drag behavior.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetLongPressSelectionRegressionTest {
    private companion object {
        const val INPUT_EDITOR_TAG = "input_editor"
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPress_reachesTheEmbeddedEditor() {
        val inputValue = mutableStateOf(TextFieldValue("selection handle target", TextRange(23)))
        val selectionEvents = Collections.synchronizedList(mutableListOf<TextRange>())
        val touchEvents = Collections.synchronizedList(mutableListOf<Int>())
        lateinit var inputMethodManager: InputMethodManager

        composeRule.setContent {
            CompositionLocalProvider(
                LocalBenchmarkAnchorConfig provides BenchmarkAnchorConfig(enabled = true),
            ) {
                MaterialTheme {
                    InputSheet(
                        state = InputSheetState(inputValue = inputValue.value),
                        callbacks =
                            InputSheetCallbacks(
                                onInputValueChange = {
                                    inputValue.value = it
                                    selectionEvents += it.selection
                                },
                                onDismiss = {},
                                onSubmit = {},
                                onImageClick = {},
                            ),
                        benchmarkEditorTag = INPUT_EDITOR_TAG,
                    )
                }
            }
        }

        val editor = waitForEditor()
        composeRule.runOnUiThread {
            inputMethodManager = checkNotNull(composeRule.activity.getSystemService())
            editor.setOnTouchListener { _, event ->
                touchEvents += event.actionMasked
                false
            }
        }
        waitForFocusAndIme(editor, inputMethodManager)
        composeRule.onNodeWithTag(INPUT_EDITOR_TAG).performTouchInput {
            longClick(Offset(x = 64f, y = centerY))
        }
        waitForTouchEvents(editor, inputMethodManager, inputValue, selectionEvents, touchEvents)
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

    private fun waitForTouchEvents(
        editor: EditText,
        inputMethodManager: InputMethodManager,
        inputValue: androidx.compose.runtime.MutableState<TextFieldValue>,
        selectionEvents: List<TextRange>,
        touchEvents: List<Int>,
        timeoutMillis: Long = 5_000,
    ) {
        var lastSnapshot = ""
        val startNanos = System.nanoTime()
        while ((System.nanoTime() - startNanos) / 1_000_000 < timeoutMillis) {
            composeRule.runOnUiThread {
                lastSnapshot =
                    "focus=${editor.hasFocus()}, ime=${inputMethodManager.isActive(editor)}, " +
                        "selectionStart=${editor.selectionStart}, selectionEnd=${editor.selectionEnd}, " +
                        "isTextSelectable=${editor.isTextSelectable}, movementMethod=${editor.movementMethod?.javaClass?.simpleName}, " +
                        "modelSelection=${inputValue.value.selection}, " +
                        "selectionEvents=${selectionEvents.joinToString()}, " +
                        "touchEvents=${touchEvents.joinToString()}, " +
                        "text=${editor.text}"
            }
            if (MotionEvent.ACTION_DOWN in touchEvents && MotionEvent.ACTION_UP in touchEvents) {
                return
            }
            Thread.sleep(100)
        }
        assertTrue(
            "Expected the embedded editor to receive ACTION_DOWN and ACTION_UP from long-press injection, but last editor state was: $lastSnapshot",
            MotionEvent.ACTION_DOWN in touchEvents && MotionEvent.ACTION_UP in touchEvents,
        )
    }

    private fun waitForFocusAndIme(
        editor: EditText,
        inputMethodManager: InputMethodManager,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            var ready = false
            composeRule.runOnUiThread {
                ready = editor.hasFocus() && inputMethodManager.isActive(editor)
            }
            ready
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
