package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: mergePagedVisibleItemsWithRetainedRows / resolvePagedRetentionCleanupIds
 * - Behavior focus: a deleted paged row must stay in the rendered list while its id is retained,
 *   and cleanup may only start after the row disappears from the latest paging snapshot.
 * - Observable outcomes: merged row order and cleanup id selection.
 * - Red phase: Fails before the fix because the paged path has no retention policy helper, so the
 *   deleted row disappears as soon as Paging invalidates.
 * - Excludes: Compose runtime animation frames, LazyPagingItems internals, and ViewModel wiring.
 */
class PagedMemoRetentionPolicyTest : AppFunSpec() {
    init {
        test("retained paged row stays visible after latest paging snapshot removes it") {
            val previousVisibleItems =
                listOf(
                    visibleItem("first"),
                    visibleItem("deleting"),
                    visibleItem("last"),
                )
            val sourceItems =
                listOf(
                    visibleItem("first"),
                    visibleItem("last"),
                )

            val result =
                mergePagedVisibleItemsWithRetainedRows(
                    sourceItems = sourceItems,
                    previousVisibleItems = previousVisibleItems,
                    retainedIds = setOf("deleting"),
                    itemId = VisibleItem::id,
                )

            (result.map(VisibleItem::id)) shouldBe (listOf("first", "deleting", "last"))
        }
    }

    init {
        test("cleanup only starts for retained ids missing from latest paging snapshot") {
            val sourceItems =
                listOf(
                    visibleItem("still-present"),
                    visibleItem("fresh"),
                )

            val result =
                resolvePagedRetentionCleanupIds(
                    sourceItems = sourceItems,
                    retainedIds = setOf("still-present", "already-removed"),
                    itemId = VisibleItem::id,
                )

            (result) shouldBe (setOf("already-removed"))
        }
    }

    private fun visibleItem(id: String): VisibleItem = VisibleItem(id = id)

    private data class VisibleItem(
        val id: String,
    )
}
