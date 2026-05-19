package com.lomo.ui.text

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


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import android.os.Build
import android.text.Layout

/*
 * Behavior Contract:
 * - Unit under test: Memo paragraph Android layout policy.
 * - Behavior focus: share-card/plain Android layout policy remains deterministic while memo body
 *   rendering is owned by the Compose-native paragraph engine.
 * - Observable outcomes: selected Android justification mode, selected hyphenation frequency,
 *   selected paragraph alignment, and strict CJK justification eligibility.
 * - TDD proof: Fails before the fix because CJK justification eligibility ignored SDK and
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
class MemoParagraphLayoutPolicyTest : UiComponentsFunSpec() {
    init {
        test("pure cjk paragraph uses inter-character justification on api 35 and above") {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认在支持的平台上使用字间两端对齐。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )

        (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
        (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        (policy.hyphenationFrequency) shouldBe (Layout.HYPHENATION_FREQUENCY_NONE)
        }
    }

    init {
        test("pure cjk paragraph disables strict justification when platform letter spacing is applied") {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认自定义字间距不会和字符级两端对齐叠加。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                platformLetterSpacing = 0.1f,
            )

        (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
        (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_NONE)
        (policy.hyphenationFrequency) shouldBe (Layout.HYPHENATION_FREQUENCY_NORMAL)
        (policy.shouldUseStrictCjkJustification) shouldBe false
        }
    }

    init {
        test("zero platform letter spacing does not disable cjk strict justification") {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认零字间距仍然可以使用字符级两端对齐。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                platformLetterSpacing = 0f,
            )

        (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        (policy.shouldUseStrictCjkJustification) shouldBe true
        }
    }

    init {
        test("pure cjk paragraph falls back to non-justify policy below api 35") {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "这是一段纯中文长段落，用来确认低版本不会强行走不受支持的字间对齐。",
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )

        (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
        (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_NONE)
        (policy.hyphenationFrequency) shouldBe (Layout.HYPHENATION_FREQUENCY_NORMAL)
        }
    }

    init {
        test("mixed memo paragraph avoids forced android strict justify") {
        val policy =
            resolveMemoParagraphLayoutPolicy(
                text = "今天阅读 README 与设计笔记。",
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            )

        (policy.alignment) shouldBe (Layout.Alignment.ALIGN_NORMAL)
        (policy.justificationMode) shouldBe (Layout.JUSTIFICATION_MODE_NONE)
        (policy.shouldUseStrictCjkJustification) shouldBe false
        }
    }
}
