package com.lomo.ui.component.card

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoCard URL-only visibility on a real device.
 * - Behavior focus: a memo whose visible body is only one bare URL must still render a visible
 *   body TextView in the card instead of collapsing to an empty-looking state.
 * - Observable outcomes: URL-bearing body TextView presence, shown state, non-empty text, and a
 *   non-empty global visible rect after composition.
 * - Red phase: Fails before the fix when a URL-only memo card does not expose any visible body
 *   TextView containing the bare URL on device.
 * - Excludes: screenshot pixel diffs, image loading, and full app navigation.
 */
@RunWith(AndroidJUnit4::class)
class MemoCardUrlOnlyVisibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun urlOnlyMemo_keepsVisibleBodyTextInCard() {
        val targetUrl = "https://example.com/path?q=visible-only"
        val renderPlan =
            createModernMarkdownRenderPlan(
                content = targetUrl,
                knownTagsToStrip = emptyList(),
            )
        assertFalse(
            "Expected a URL-only memo to produce at least one render-plan item on device.",
            renderPlan.items.isEmpty(),
        )

        composeRule.setContent {
            MaterialTheme {
                MemoCard(
                    content = targetUrl,
                    processedContent = targetUrl,
                    precomputedRenderPlan = renderPlan,
                    timestamp = 1L,
                    tags = persistentListOf(),
                    allowFreeTextCopy = true,
                    shouldShowExpand = false,
                )
            }
        }

        val bodyTextView = waitForTextView(targetUrl)
        val visibleRect = Rect()

        composeRule.runOnUiThread {
            assertTrue(bodyTextView.isShown)
            assertTrue(bodyTextView.text.toString().contains(targetUrl))
            assertTrue(bodyTextView.layout != null)
            assertTrue(bodyTextView.lineCount > 0)
            assertTrue(bodyTextView.getGlobalVisibleRect(visibleRect))
            assertTrue(visibleRect.width() > 0)
            assertTrue(visibleRect.height() > 0)
        }
    }

    private fun waitForTextView(
        expectedText: String,
        timeoutMillis: Long = 5_000,
    ): TextView {
        var textView: TextView? = null
        var lastSnapshot = ""
        composeRule.waitUntil(timeoutMillis) {
            composeRule.runOnUiThread {
                val root = composeRule.activity.findViewById<View>(android.R.id.content)
                textView = findTextView(root, expectedText)
                lastSnapshot = dumpTextViews(root)
            }
            textView != null
        }
        return checkNotNull(textView) {
            "Expected to find a TextView containing `$expectedText`, but only saw: $lastSnapshot"
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

    private fun dumpTextViews(root: View): String {
        val texts = mutableListOf<String>()
        collectTextViews(root, texts)
        return texts.joinToString(separator = " | ").ifBlank { "<no TextView/EditText descendants>" }
    }

    private fun collectTextViews(
        root: View,
        texts: MutableList<String>,
    ) {
        if (root is TextView) {
            texts += "${root.javaClass.simpleName}(shown=${root.isShown}, text=${root.text})"
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                collectTextViews(root.getChildAt(index), texts)
            }
        }
    }
}
