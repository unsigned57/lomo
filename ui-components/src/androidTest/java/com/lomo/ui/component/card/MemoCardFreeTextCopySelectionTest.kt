package com.lomo.ui.component.card

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.R
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoCard free-text-copy selection behavior.
 * - Behavior focus: when free-text copy is enabled, a real long-press on Compose-native memo body
 *   text must enter memo selection and allow copying without delegating body display to TextView;
 *   body double-tap must still invoke edit, and when free copy is disabled the same long-press
 *   must not expose memo copy chrome.
 * - Observable outcomes: Compose text semantics, absence of a body TextView, copy toolbar
 *   presence/absence, copied clipboard text after the Compose copy action, and edit callback count.
 * - Red phase: Fails before the migration because memo body selection/copy is owned by TextView
 *   instead of Compose-native selection state and copy chrome; the body double-tap companion fails
 *   before the fix because enabling free copy removes the card-level combinedClickable handler and
 *   the Compose text node does not forward double-tap to edit.
 * - Excludes: selection handle pixels, exact selected word heuristics, and OEM-specific action menus.
 *
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the test inspected the underlying memo body TextView
 *   selection state.
 * - Why old assertion is no longer correct: memo body display, selection, and copy have migrated
 *   to a Compose Canvas/semantics implementation.
 * - Coverage preserved by: long-pressing the Compose text semantics node, using the Compose copy
 *   action, checking the clipboard side effect, and asserting no body TextView is created.
 * - Why this is not fitting the test to the implementation: the new assertions lock the requested
 *   no-TextView migration contract while preserving the user-visible copy behavior.
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

        val copyLabel = composeRule.activity.getString(R.string.action_copy)
        replaceClipboardText("")
        composeRule.onNodeWithText(targetText, substring = true).assertExists()
        assertNoBodyTextView(targetText)

        composeRule.onNodeWithText(targetText, substring = true).performTouchInput {
            longClick()
        }
        waitForText(copyLabel)
        composeRule.onNodeWithText(copyLabel, useUnmergedTree = true).performClick()

        composeRule.waitUntil(5_000) {
            val copiedText = clipboardText()
            copiedText.isNotBlank() && targetText.contains(copiedText)
        }
    }

    @Test
    fun doubleTap_editsReadyMarkdownText_whenFreeCopyEnabled() {
        val targetText = "Memo body double tap edit target"
        val editCount = AtomicInteger(0)
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
                    onDoubleClick = { editCount.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText(targetText, substring = true).assertExists()

        composeRule.onNodeWithText(targetText, substring = true).performTouchInput {
            doubleClick()
        }
        composeRule.waitForIdle()

        assertEquals(1, editCount.get())
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

        val copyLabel = composeRule.activity.getString(R.string.action_copy)
        composeRule.onNodeWithText(targetText, substring = true).assertExists()
        assertNoBodyTextView(targetText)

        composeRule.onNodeWithText(targetText, substring = true).performTouchInput {
            longClick()
        }
        composeRule.waitForIdle()

        assertFalse(hasText(copyLabel))
    }

    private fun waitForText(
        expectedText: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) { hasText(expectedText) }
    }

    private fun hasText(expectedText: String): Boolean =
        composeRule
            .onAllNodesWithText(expectedText, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun assertNoBodyTextView(expectedText: String) {
        composeRule.runOnUiThread {
            assertNull(findTextView(composeRule.activity.findViewById(android.R.id.content), expectedText))
        }
    }

    private fun replaceClipboardText(text: String) {
        composeRule.runOnUiThread {
            clipboardManager().setPrimaryClip(ClipData.newPlainText("memo-test", text))
        }
    }

    private fun clipboardText(): String {
        var value = ""
        composeRule.runOnUiThread {
            val clip = clipboardManager().primaryClip
            value = clip?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString().orEmpty()
        }
        return value
    }

    private fun clipboardManager(): ClipboardManager =
        composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

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
