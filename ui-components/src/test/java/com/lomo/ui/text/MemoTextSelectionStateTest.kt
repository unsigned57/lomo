package com.lomo.ui.text

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoTextSelectionState — the per-paragraph selection state holder used
 *   by the compose-native memo body text renderer.
 * - Behavior focus: selection ranges resolve against the original (un-normalized) source
 *   text offsets, drag direction is normalized so the returned range always reads forward,
 *   and a collapsed or cleared selection yields no copied text.
 * - Observable outcomes: normalized selected range, copied plain text, cleared selection
 *   state.
 * - Red phase: Fails before the fix because memo body copy selection is delegated to
 *   TextView and has no Compose-native selection state contract.
 * - Excludes: Android clipboard service, floating toolbar placement, drag gesture dispatch,
 *   rendered selection handle pixels, and link activation policy (now covered by
 *   MemoParagraphTapOutcomeTest).
 */
class MemoTextSelectionStateTest : UiComponentsFunSpec() {
    init {
        test("selected text uses original offsets without layout spacing") {
            val text = "中文 review memo"
            val state = MemoTextSelectionState(anchorOffset = 0, focusOffset = 9)

            state.selectedRange shouldBe (0 until 9)
            state.selectedText(text) shouldBe "中文 review"
        }

        test("selection normalizes reversed drag direction") {
            val text = "今天 review memo"
            val state = MemoTextSelectionState(anchorOffset = 10, focusOffset = 3)

            state.selectedRange shouldBe (3 until 10)
            state.selectedText(text) shouldBe "review "
        }

        test("collapsed and cleared selections copy no text") {
            val text = "不会复制"
            val collapsed = MemoTextSelectionState(anchorOffset = 2, focusOffset = 2)

            collapsed.isCollapsed shouldBe true
            collapsed.selectedText(text) shouldBe ""
            collapsed.clear().hasSelection shouldBe false
        }
    }
}
