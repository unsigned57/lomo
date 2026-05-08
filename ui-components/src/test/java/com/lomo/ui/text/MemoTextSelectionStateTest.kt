package com.lomo.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoTextSelectionState and memo link activation policy.
 * - Behavior focus: Compose-native memo text selection must preserve source-text offsets for
 *   copying and prevent link taps while a selection is active.
 * - Observable outcomes: normalized selected range, copied plain text, cleared selection state,
 *   and link activation decisions.
 * - Red phase: Fails before the fix because memo body copy selection is delegated to TextView and
 *   has no Compose-native selection state contract.
 * - Excludes: Android clipboard service, floating toolbar placement, drag gesture dispatch, and
 *   rendered selection handle pixels.
 */
class MemoTextSelectionStateTest {
    @Test
    fun `selected text uses original offsets without layout spacing`() {
        val text = "中文 review memo"
        val state = MemoTextSelectionState(anchorOffset = 0, focusOffset = 9)

        assertEquals(0 until 9, state.selectedRange)
        assertEquals("中文 review", state.selectedText(text))
    }

    @Test
    fun `selection normalizes reversed drag direction`() {
        val text = "今天 review memo"
        val state = MemoTextSelectionState(anchorOffset = 10, focusOffset = 3)

        assertEquals(3 until 10, state.selectedRange)
        assertEquals("review ", state.selectedText(text))
    }

    @Test
    fun `collapsed and cleared selections copy no text`() {
        val text = "不会复制"
        val collapsed = MemoTextSelectionState(anchorOffset = 2, focusOffset = 2)

        assertTrue(collapsed.isCollapsed)
        assertEquals("", collapsed.selectedText(text))
        assertFalse(collapsed.clear().hasSelection)
    }

    @Test
    fun `active selection suppresses link activation`() {
        val activeSelection = MemoTextSelectionState(anchorOffset = 0, focusOffset = 2)
        val noSelection = MemoTextSelectionState.None
        val link = MemoTextLinkRange(start = 3, end = 9, url = "https://example.com")

        assertFalse(shouldActivateMemoTextLink(activeSelection, offset = 4, links = listOf(link)))
        assertTrue(shouldActivateMemoTextLink(noSelection, offset = 4, links = listOf(link)))
        assertFalse(shouldActivateMemoTextLink(noSelection, offset = 9, links = listOf(link)))
    }
}
