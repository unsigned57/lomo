package com.lomo.ui.component.card

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoCard URL-only visibility on a real device.
 * - Behavior focus: a memo whose visible body is only one bare URL must still render a visible
 *   Compose body node in the card instead of collapsing to an empty-looking state.
 * - Observable outcomes: URL-bearing Compose text semantics presence, displayed state, non-empty
 *   bounds after composition, and absence of a body TextView containing the URL.
 * - Red phase: Fails before the migration because URL-only memo visibility was verified through
 *   a body TextView instead of the Compose-native memo paragraph semantics node.
 * - Excludes: screenshot pixel diffs, image loading, and full app navigation.
 *
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the test required a visible URL-bearing body TextView.
 * - Why old assertion is no longer correct: memo body display has migrated to Compose Canvas
 *   drawing with text semantics instead of AndroidView/TextView.
 * - Coverage preserved by: asserting displayed Compose text semantics, non-empty bounds, and no
 *   URL-bearing body TextView.
 * - Why this is not fitting the test to the implementation: the new assertions capture the
 *   requested no-TextView migration while preserving the URL-only visibility regression boundary.
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

        val bodyNode =
            composeRule
                .onNodeWithText(targetUrl, substring = true)
                .assertExists()
                .assertIsDisplayed()

        val bounds = bodyNode.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 0f)
        assertTrue(bounds.height > 0f)

        composeRule.runOnUiThread {
            val root = composeRule.activity.findViewById<View>(android.R.id.content)
            assertNull(findTextView(root, targetUrl))
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
