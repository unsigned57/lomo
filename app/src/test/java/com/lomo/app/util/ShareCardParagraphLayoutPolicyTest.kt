package com.lomo.app.util

import android.text.Layout
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: resolveShareCardParagraphLayoutPolicy
 * - Behavior focus: share-card paragraph layout policy for pure CJK prose, mixed-script prose, and centered short-body rendering.
 * - Observable outcomes: selected Layout alignment, justification mode, break strategy, and hyphenation frequency for a paragraph.
 * - Red phase: Fails before the fix because share-card image rendering does not yet expose or apply the memo CJK paragraph layout policy to StaticLayout.
 * - Excludes: bitmap drawing, TextPaint sizing, resource lookups, and markdown-to-plain-text cleanup.
 */
class ShareCardParagraphLayoutPolicyTest {
    @Test
    fun `pure cjk paragraph uses inter-character justification in share card rendering`() {
        val policy =
            resolveShareCardParagraphLayoutPolicy(
                text = "这是一段很长的中文引号段落内容：应该在分享图片里保持稳定的中文段落排版与两端对齐。",
                shouldUseCenteredBody = false,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, policy.justificationMode)
        assertEquals(Layout.BREAK_STRATEGY_HIGH_QUALITY, policy.breakStrategy)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NONE, policy.hyphenationFrequency)
    }

    @Test
    fun `mixed prose keeps normal paragraph layout in share card rendering`() {
        val policy =
            resolveShareCardParagraphLayoutPolicy(
                text = "这段 memo 同时包含 module-a/v2、quoted text 和 README.md，不应该误切到纯中文 justify。",
                shouldUseCenteredBody = false,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
    }

    @Test
    fun `centered short body stays centered without forced justification`() {
        val policy =
            resolveShareCardParagraphLayoutPolicy(
                text = "短句中文",
                shouldUseCenteredBody = true,
            )

        assertEquals(Layout.Alignment.ALIGN_CENTER, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
    }
}
