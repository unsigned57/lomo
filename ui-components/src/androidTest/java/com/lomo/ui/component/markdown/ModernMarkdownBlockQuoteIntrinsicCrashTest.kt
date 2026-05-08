package com.lomo.ui.component.markdown

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: ModernMarkdownRenderer block-quote rendering in ui-components.
 * - Behavior focus: markdown block quotes must render Compose-native memo text without asking
 *   SubcomposeLayout-backed text for intrinsic measurements.
 * - Observable outcomes: quoted text is displayed after composition instead of crashing the app.
 * - Red phase: Fails before the fix with "Asking for intrinsic measurements of SubcomposeLayout
 *   layouts is not supported" when a block quote contains MemoParagraphText.
 * - Excludes: pixel-perfect quote indicator height, markdown parser internals, and text glyph
 *   rasterization.
 */
@RunWith(AndroidJUnit4::class)
class ModernMarkdownBlockQuoteIntrinsicCrashTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun blockQuote_rendersComposeMemoText_withoutIntrinsicMeasurementCrash() {
        val quoteText = "引用里的 memo 文本"

        composeRule.setContent {
            MaterialTheme {
                ModernMarkdownRenderer(
                    content = "> $quoteText",
                    enableTextSelection = true,
                )
            }
        }

        composeRule
            .onNodeWithText(quoteText, substring = true)
            .assertIsDisplayed()
    }
}
