package com.lomo.app.feature.main

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo list horizontal padding policy.
 * - Behavior focus: enabling the draggable scrollbar must not move memo cards off center by
 *   increasing only the trailing content padding.
 * - Observable outcomes: resolved start/end memo content padding for scrollbar on and off states.
 * - Red phase: Fails before the fix because the list uses a larger end padding when the scrollbar
 *   is enabled, making memo cards visually misaligned.
 * - Excludes: Compose LazyColumn rendering, scrollbar drag behavior, and navigation bar insets.
 */
class MemoListScrollbarPaddingPolicyTest {
    @Test
    fun `memo side padding stays balanced when scrollbar is disabled`() {
        val padding = resolveMemoListHorizontalContentPadding(scrollbarEnabled = false)

        assertEquals(16.dp, padding.start)
        assertEquals(padding.start, padding.end)
    }

    @Test
    fun `memo side padding stays balanced when scrollbar is enabled`() {
        val padding = resolveMemoListHorizontalContentPadding(scrollbarEnabled = true)

        assertEquals(16.dp, padding.start)
        assertEquals(padding.start, padding.end)
    }
}
