package com.lomo.app.util

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import android.text.Layout
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: resolveShareCardParagraphLayoutPolicy
 * - Behavior focus: share-card paragraph layout policy for pure CJK prose, mixed-script prose, and centered short-body rendering.
 * - Observable outcomes: selected Layout alignment, justification mode, break strategy, and hyphenation frequency for a paragraph.
 * - TDD proof: Fails before the fix because share-card image rendering does not yet expose or apply the memo CJK paragraph layout policy to StaticLayout.
 * - Excludes: bitmap drawing, TextPaint sizing, resource lookups, and markdown-to-plain-text cleanup.
 */
class ShareCardParagraphLayoutPolicyTest : AppFunSpec() {
    init {
        test("pure cjk paragraph uses inter-character justification in share card rendering") {
            val policy =
                resolveShareCardParagraphLayoutPolicy(
                    text = "这是一段很长的中文引号段落内容：应该在分享图片里保持稳定的中文段落排版与两端对齐。",
                    shouldUseCenteredBody = false,
                )

            (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
            (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
            (policy.breakStrategy) shouldBe (Layout.BREAK_STRATEGY_HIGH_QUALITY)
            (policy.hyphenationFrequency) shouldBe (Layout.HYPHENATION_FREQUENCY_NONE)
        }
    }

    init {
        test("mixed prose keeps normal paragraph layout in share card rendering") {
            val policy =
                resolveShareCardParagraphLayoutPolicy(
                    text = "这段 memo 同时包含 module-a/v2、quoted text 和 README.md，不应该误切到纯中文 justify。",
                    shouldUseCenteredBody = false,
                )

            (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
            (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_NONE)
        }
    }

    init {
        test("centered short body stays centered without forced justification") {
            val policy =
                resolveShareCardParagraphLayoutPolicy(
                    text = "短句中文",
                    shouldUseCenteredBody = true,
                )

            (policy.alignment) shouldBe (Layout.Alignment.ALIGN_CENTER)
            (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_NONE)
        }
    }

}
