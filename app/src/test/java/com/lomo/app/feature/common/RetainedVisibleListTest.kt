package com.lomo.app.feature.common

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: mergeVisibleItemsWithRetainedItems, resolveRetentionCleanupIds
 * - Behavior focus: retained rows stay visible in correct positions during delete animation;
 *   concurrent deletions preserve insertion-index stability.
 * - Observable outcomes: returned visible item ids; position stability under multi-item retention.
 * - Red phase: Fails before the fix when concurrent deletions cause the second retained item
 *   to displace into the wrong position because the insertion index is computed from stale anchors.
 * - Excludes: Compose rendering, LazyList scrolling, animation frame timing, and ViewModel wiring.
 */
class RetainedVisibleListTest : AppFunSpec() {
    init {
        test("no retained items immediately adopt latest source order") {
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

            (result.map(VisibleItem::id)) shouldBe (listOf("newest", "middle", "oldest"))
        }
    }

    init {
        test("retained items stay visible while remaining source items follow latest order") {
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

            (result.map(VisibleItem::id)) shouldBe (listOf("newest", "stale-retained", "oldest"))
        }
    }

    init {
        test("concurrent retained items keep stable positions") {
            val previousVisibleItems =
                listOf(
                    visibleItem("A"),
                    visibleItem("del_1"),
                    visibleItem("B"),
                    visibleItem("del_2"),
                    visibleItem("C"),
                )
            val sourceItems =
                listOf(
                    visibleItem("A"),
                    visibleItem("B"),
                    visibleItem("C"),
                )

            val result =
                mergeVisibleItemsWithRetainedItems(
                    sourceItems = sourceItems,
                    previousVisibleItems = previousVisibleItems,
                    retainedIds = setOf("del_1", "del_2"),
                    itemId = VisibleItem::id,
                )

            (result.map(VisibleItem::id)) shouldBe (listOf("A", "del_1", "B", "del_2", "C"))
        }
    }

    init {
        test("resolveRetentionCleanupIds returns only ids absent from source") {
            val sourceItems =
                listOf(
                    visibleItem("A"),
                    visibleItem("B"),
                )
            val retainedIds = setOf("del_1", "del_2", "del_3")

            val cleanupIds =
                resolveRetentionCleanupIds(
                    sourceItems = sourceItems,
                    retainedIds = retainedIds,
                    itemId = VisibleItem::id,
                )

            (cleanupIds) shouldBe (setOf("del_1", "del_2", "del_3"))
        }
    }

    init {
        test("resolveRetentionCleanupIds excludes ids still in source") {
            val sourceItems =
                listOf(
                    visibleItem("A"),
                    visibleItem("del_1"),
                )
            val retainedIds = setOf("del_1", "del_2")

            val cleanupIds =
                resolveRetentionCleanupIds(
                    sourceItems = sourceItems,
                    retainedIds = retainedIds,
                    itemId = VisibleItem::id,
                )

            (cleanupIds) shouldBe (setOf("del_2"))
        }
    }

    private fun visibleItem(id: String): VisibleItem = VisibleItem(id = id)

    private data class VisibleItem(
        val id: String,
    )
}
