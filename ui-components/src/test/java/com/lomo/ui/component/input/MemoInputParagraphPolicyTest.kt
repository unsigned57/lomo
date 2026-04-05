package com.lomo.ui.component.input

import android.text.Layout
import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input paragraph layout policy.
 * - Behavior focus: the editor must keep the same paragraph-layout policy for empty text, latin text, and first CJK input so the first composed CJK character does not switch the editor into a different measurement path.
 * - Observable outcomes: stable gravity, text alignment, justification, break strategy, and hyphenation policy across representative input scripts.
 * - Red phase: Fails before the fix because the input bridge derives its layout policy from the current text content, so the first CJK character can flip the editor into a different platform paragraph mode.
 * - Excludes: OEM font fallback metrics, IME composition timing, and Compose sheet animations.
 */
class MemoInputParagraphPolicyTest {
    @Test
    fun `layout policy stays identical across empty latin and cjk input`() {
        val emptyPolicy = resolveMemoInputParagraphLayoutPolicy("")
        val latinPolicy = resolveMemoInputParagraphLayoutPolicy("a")
        val cjkPolicy = resolveMemoInputParagraphLayoutPolicy("你")

        assertEquals(emptyPolicy, latinPolicy)
        assertEquals(emptyPolicy, cjkPolicy)
        assertEquals(Gravity.START or Gravity.TOP, emptyPolicy.gravity)
        assertEquals(android.view.View.TEXT_ALIGNMENT_VIEW_START, emptyPolicy.textAlignment)
        assertEquals(Layout.JUSTIFICATION_MODE_NONE, emptyPolicy.justificationMode)
        assertEquals(Layout.BREAK_STRATEGY_HIGH_QUALITY, emptyPolicy.breakStrategy)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NONE, emptyPolicy.hyphenationFrequency)
    }
}
