package com.lomo.ui.component.input

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: selection and insertion handle drawable availability for editable memo input.
 * - Behavior focus: both a plain platform EditText control and the InputSheet embedded editor must
 *   expose non-null, non-zero-sized handle drawables after focus and selection are established.
 * - Observable outcomes: getTextSelectHandle(), getTextSelectHandleLeft(), and
 *   getTextSelectHandleRight() return visible drawables on device.
 * - Red phase: Fails before the fix when the memo editor styling path clears or invalidates the
 *   platform handle drawables, making long-press editing controls invisible.
 * - Excludes: handle tint correctness, OEM artwork differences, and drag gesture behavior.
 */
@RunWith(AndroidJUnit4::class)
class SelectionHandleDrawableRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun plainEditText_exposesVisibleHandleDrawables() {
        composeRule.setContent {
            PlainEditTextHost(text = "selection handle target")
        }

        val editor = waitForEditor()
        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.requestFocusFromTouch()
            editor.setSelection(0, 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertVisibleHandleDrawables(editor, label = "plain EditText")
        }
    }

    @Test
    fun inputSheetEditor_exposesVisibleHandleDrawables() {
        val inputValue = mutableStateOf(TextFieldValue("selection handle target", TextRange(23)))

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
        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.requestFocusFromTouch()
            editor.setSelection(0, 1)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertVisibleHandleDrawables(editor, label = "InputSheet editor")
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

    private fun assertVisibleHandleDrawables(
        editor: EditText,
        label: String,
    ) {
        assertVisibleDrawable("$label insertion handle", editor.textSelectHandle)
        assertVisibleDrawable("$label left selection handle", editor.textSelectHandleLeft)
        assertVisibleDrawable("$label right selection handle", editor.textSelectHandleRight)
    }

    private fun assertVisibleDrawable(
        label: String,
        drawable: Drawable?,
    ) {
        assertNotNull("$label should not be null", drawable)
        val resolved = checkNotNull(drawable)
        assertTrue("$label should have positive intrinsic width", resolved.intrinsicWidth > 0)
        assertTrue("$label should have positive intrinsic height", resolved.intrinsicHeight > 0)
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

@Composable
private fun PlainEditTextHost(text: String) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            EditText(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                setText(text)
                setSelection(text.length)
            }
        },
    )
}
