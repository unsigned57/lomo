package com.lomo.ui.component.card

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoCard free-text-copy selection behavior.
 * - Behavior focus: when free-text copy is enabled, a real long-press on memo card body text must
 *   enter platform text selection even after markdown is pre-rendered; when disabled, the same
 *   long-press must not create a text selection.
 * - Observable outcomes: underlying TextView selection state after injected long-press gestures.
 * - Red phase: Fails before the fix because root SelectionContainer wrappers around memo-body and
 *   markdown content intercept the long-press path, so the platform TextView never enters a
 *   selection state even though the preference flag is enabled.
 * - Excludes: selection handle visuals, copy toolbar strings, and OEM-specific text-action menus.
 */
@RunWith(AndroidJUnit4::class)
class MemoCardFreeTextCopySelectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPress_selectsReadyMarkdownText_whenFreeCopyEnabled() {
        val targetText = "Memo ready selection target"
        val renderPlan =
            createModernMarkdownRenderPlan(
                content = targetText,
                knownTagsToStrip = emptyList(),
            )

        composeRule.setContent {
            MaterialTheme {
                MemoCard(
                    content = targetText,
                    processedContent = targetText,
                    precomputedRenderPlan = renderPlan,
                    timestamp = 1L,
                    tags = persistentListOf(),
                    allowFreeTextCopy = true,
                    shouldShowExpand = false,
                )
            }
        }

        val bodyTextView = waitForTextView(targetText)
        longPressViewCenter(bodyTextView)
        waitForSelection(bodyTextView)
    }

    @Test
    fun longPress_doesNotSelectReadyMarkdownText_whenFreeCopyDisabled() {
        val targetText = "Memo disabled selection target"
        val renderPlan =
            createModernMarkdownRenderPlan(
                content = targetText,
                knownTagsToStrip = emptyList(),
            )

        composeRule.setContent {
            MaterialTheme {
                MemoCard(
                    content = targetText,
                    processedContent = targetText,
                    precomputedRenderPlan = renderPlan,
                    timestamp = 1L,
                    tags = persistentListOf(),
                    allowFreeTextCopy = false,
                    shouldShowExpand = false,
                )
            }
        }

        val bodyTextView = waitForTextView(targetText)
        longPressViewCenter(bodyTextView)
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(bodyTextView.hasSelection())
            assertTrue(bodyTextView.selectionStart < 0 || bodyTextView.selectionStart == bodyTextView.selectionEnd)
        }
    }

    private fun longPressViewCenter(view: View) {
        val location = IntArray(2)
        composeRule.runOnUiThread {
            view.getLocationOnScreen(location)
        }
        val centerX = location[0] + (view.width / 2)
        val centerY = location[1] + (view.height / 2)
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand("input touchscreen swipe $centerX $centerY $centerX $centerY 800")
            .close()
    }

    private fun waitForTextView(
        expectedText: String,
        timeoutMillis: Long = 5_000,
    ): TextView {
        var textView: TextView? = null
        composeRule.waitUntil(timeoutMillis) {
            composeRule.runOnUiThread {
                textView = findTextView(composeRule.activity.findViewById(android.R.id.content), expectedText)
            }
            textView != null
        }
        return checkNotNull(textView)
    }

    private fun waitForSelection(
        textView: TextView,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            var hasSelection = false
            composeRule.runOnUiThread {
                hasSelection =
                    textView.hasSelection() &&
                        textView.selectionStart >= 0 &&
                        textView.selectionEnd > textView.selectionStart
            }
            hasSelection
        }

        composeRule.runOnUiThread {
            assertTrue(textView.hasSelection())
            assertTrue(textView.selectionStart >= 0)
            assertTrue(textView.selectionEnd > textView.selectionStart)
        }
    }

    private fun findTextView(
        root: View,
        expectedText: String,
    ): TextView? {
        if (root is TextView && root.text?.toString()?.contains(expectedText) == true) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), expectedText)?.let { return it }
            }
        }
        return null
    }
}
