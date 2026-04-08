package com.lomo.ui.component.input

import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: control long-press selection behavior of a plain platform EditText on the same device.
 * - Behavior focus: the instrumentation gesture used by InputSheetLongPressSelectionRegressionTest
 *   must at least reach a standard focused EditText touch pipeline.
 * - Observable outcomes: received MotionEvent actions after a Compose-injected long-press gesture.
 * - Red phase: Intended as an environment control; if this fails, the device gesture path itself is
 *   not trustworthy for diagnosing the custom memo editor.
 * - Excludes: custom bridge logic, range-handle visuals, and OEM-specific selection heuristics.
 */
@RunWith(AndroidJUnit4::class)
class PlainEditTextLongPressSelectionControlTest {
    private companion object {
        const val PLAIN_EDITOR_TAG = "plain_editor"
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPress_reachesPlainEditTextTouchPipeline() {
        val touchEvents = Collections.synchronizedList(mutableListOf<Int>())
        composeRule.setContent {
            PlainEditTextHost(
                text = "selection handle target",
                onTouchAction = { touchEvents += it },
            )
        }

        val editor = waitForEditor()
        composeRule.runOnUiThread {
            editor.requestFocus()
            editor.requestFocusFromTouch()
            editor.setSelection(editor.text.length)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PLAIN_EDITOR_TAG).performTouchInput {
            longClick(Offset(x = 48f, y = centerY))
        }
        waitForTouchEvents(touchEvents)
    }

    private fun waitForEditor(timeoutMillis: Long = 5_000): EditText {
        var editor: EditText? = null
        composeRule.waitUntil(timeoutMillis) {
            composeRule.runOnUiThread {
                editor = composeRule.activity.findViewById<View>(android.R.id.content).findFirstEditText()
            }
            editor != null
        }
        return checkNotNull(editor)
    }

    private fun waitForTouchEvents(
        touchEvents: List<Int>,
        timeoutMillis: Long = 5_000,
    ) {
        val startNanos = System.nanoTime()
        while ((System.nanoTime() - startNanos) / 1_000_000 < timeoutMillis) {
            if (MotionEvent.ACTION_DOWN in touchEvents && MotionEvent.ACTION_UP in touchEvents) return
            Thread.sleep(100)
        }
        assertTrue(
            "Expected a plain EditText to receive ACTION_DOWN and ACTION_UP from long-press dispatch, but observed touch events=${touchEvents.joinToString()}",
            MotionEvent.ACTION_DOWN in touchEvents && MotionEvent.ACTION_UP in touchEvents,
        )
    }
}

@Composable
private fun PlainEditTextHost(
    text: String,
    onTouchAction: (Int) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.testTag("plain_editor"),
        factory = {
            EditText(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                setText(text)
                setSelection(text.length)
                setOnTouchListener { _, event ->
                    onTouchAction(event.actionMasked)
                    false
                }
            }
        },
    )
}

private fun View.findFirstEditText(): EditText? {
    if (this is EditText) return this
    if (this is android.view.ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findFirstEditText()?.let { return it }
        }
    }
    return null
}
