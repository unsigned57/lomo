package com.lomo.ui.text

import android.os.Build
import android.text.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Memo paragraph platform layout policy and rich-paragraph platform rendering gate.
 * - Behavior focus: pure-CJK paragraphs opt into inter-character justification only on supported SDKs, mixed prose avoids forced justify, and rich memo paragraphs stay on the shared TextView path so plain/rich Chinese paragraphs keep the same layout rhythm.
 * - Observable outcomes: selected Android justification mode, selected hyphenation frequency, selected paragraph alignment, and platform paragraph renderer eligibility for rich annotated text.
 * - Red phase: Fails before the fix because rich annotated memo paragraphs still drop to Compose Text, and the memo paragraph layout policy does not expose an SDK-aware inter-character fallback contract.
 * - Excludes: Compose widget tree inspection, OEM font rasterization, TextView measurement internals, and markdown block traversal.
 */
class MemoParagraphLayoutPolicyTest {
    @Test
    fun `pure cjk paragraph uses inter-character justification on api 35 and above`() {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认在支持的平台上使用字间两端对齐。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, policy.justificationMode)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NONE, policy.hyphenationFrequency)
    }

    @Test
    fun `pure cjk paragraph falls back to non-justify policy below api 35`() {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认低版本不会强行走不受支持的字间对齐。",
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NORMAL, policy.hyphenationFrequency)
    }

    @Test
    fun `mixed rich memo paragraph keeps platform paragraph rendering while avoiding forced justify`() {
        val richText =
            buildAnnotatedString {
                append("今天阅读 ")
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append("README")
                pop()
                append(" 与设计笔记。")
            }

        val policy = resolveMemoParagraphLayoutPolicy(richText, sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM)

        assertTrue(richText.shouldUseTextViewMemoParagraphRendering())
        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
        assertFalse(policy.shouldUseStrictCjkJustification)
    }
}
