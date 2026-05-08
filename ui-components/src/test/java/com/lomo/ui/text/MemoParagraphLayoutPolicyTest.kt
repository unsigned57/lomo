package com.lomo.ui.text

import android.os.Build
import android.text.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Memo paragraph Android layout policy.
 * - Behavior focus: share-card/plain Android layout policy remains deterministic while memo body
 *   rendering is owned by the Compose-native paragraph engine.
 * - Observable outcomes: selected Android justification mode, selected hyphenation frequency,
 *   selected paragraph alignment, and strict CJK justification eligibility.
 * - Red phase: Fails before the fix because CJK justification eligibility ignored SDK and
 *   letter-spacing constraints.
 * - Excludes: Compose widget tree inspection, OEM font rasterization, TextView measurement internals, and markdown block traversal.
 *
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: this file also asserted TextView renderer flags.
 * - Why old assertion is no longer correct: those flags were removed with the legacy
 *   TextView/EditText bridge cleanup.
 * - Coverage preserved by: the Android layout policy assertions here and
 *   MemoLegacyPlatformPathRemovalContractTest.
 * - Why this is not fitting the test to the implementation: renderer-path cleanup now has one
 *   dedicated migration-boundary test.
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
    fun `pure cjk paragraph disables strict justification when platform letter spacing is applied`() {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认自定义字间距不会和字符级两端对齐叠加。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                platformLetterSpacing = 0.1f,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NORMAL, policy.hyphenationFrequency)
        assertFalse(policy.shouldUseStrictCjkJustification)
    }

    @Test
    fun `zero platform letter spacing does not disable cjk strict justification`() {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认零字间距仍然可以使用字符级两端对齐。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                platformLetterSpacing = 0f,
            )

        assertEquals(Layout.JUSTIFICATION_MODE_INTER_CHARACTER, policy.justificationMode)
        assertTrue(policy.shouldUseStrictCjkJustification)
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
    fun `mixed memo paragraph avoids forced android strict justify`() {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "今天阅读 README 与设计笔记。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )

        assertEquals(Layout.Alignment.ALIGN_NORMAL, policy.alignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, policy.justificationMode)
        assertFalse(policy.shouldUseStrictCjkJustification)
    }
}
