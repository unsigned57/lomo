package com.lomo.app.feature.common

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: mergeVisibleItemsWithRetainedItems
 * - Behavior focus: rendered memo order must immediately follow the latest source order when no items are
 *   retained, while still keeping explicitly retained rows visible during delete-collapse transitions.
 * - Observable outcomes: returned visible item ids after sort-like reordering and retained-item removal.
 * - Red phase: Fails before the fix because the merge keeps the previous visible ordering even when retainedIds
 *   is empty, which makes main-list sorting appear broken and causes Jump navigation to target the wrong row.
 * - Excludes: Compose rendering, LazyList scrolling, and ViewModel wiring.
 */
class RetainedVisibleListTest {
    @Test
    fun `no retained items immediately adopt latest source order`() {
        val previousVisibleItems =
            listOf(
                visibleItem("oldest"),
                visibleItem("middle"),
                visibleItem("newest"),
            )
        val sourceItems =
            listOf(
                visibleItem("newest"),
                visibleItem("middle"),
                visibleItem("oldest"),
            )

        val result =
            mergeVisibleItemsWithRetainedItems(
                sourceItems = sourceItems,
                previousVisibleItems = previousVisibleItems,
                retainedIds = emptySet(),
                itemId = VisibleItem::id,
            )

        assertEquals(listOf("newest", "middle", "oldest"), result.map(VisibleItem::id))
    }

    @Test
    fun `retained items stay visible while remaining source items follow latest order`() {
        val previousVisibleItems =
            listOf(
                visibleItem("stale-retained"),
                visibleItem("oldest"),
                visibleItem("newest"),
            )
        val sourceItems =
            listOf(
                visibleItem("newest"),
                visibleItem("oldest"),
            )

        val result =
            mergeVisibleItemsWithRetainedItems(
                sourceItems = sourceItems,
                previousVisibleItems = previousVisibleItems,
                retainedIds = setOf("stale-retained"),
                itemId = VisibleItem::id,
            )

        assertEquals(listOf("newest", "stale-retained", "oldest"), result.map(VisibleItem::id))
    }

    private fun visibleItem(id: String): VisibleItem = VisibleItem(id = id)

    private data class VisibleItem(
        val id: String,
    )
}
