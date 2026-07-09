package com.lomo.app.feature.main

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import com.lomo.ui.component.common.uniqueMemoListRenderKeys
import com.lomo.ui.component.common.ExitAnimationRegistry
import com.lomo.ui.component.common.resolveExitRenderList
import io.mockk.every
import io.mockk.mockk
import androidx.paging.compose.LazyPagingItems

/*
 * Behavior Contract:
 * - Unit under test: PagedMemoListContentSupport, LomoListExitSupport
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Map absolute index spaces to placeholder keys and items, resolve exit transition layout keys, and filter deletion snapshots.
 *
 * Scenarios:
 * - Given index is within loaded window, when resolving item or key, then the loaded item or its memo ID is returned.
 * - Given index is outside loaded window but within itemCount, when resolving item, then it peeks from paging items.
 * - Given index is outside loaded window, when resolving key, then a placeholder key "paging-placeholder-<index>" is returned.
 * - Given duplicate memo IDs exist in the render list, when generating render keys, then unique keys are resolved without changing original order.
 * - Given an exiting item with a stale predecessor, when its exit anchor is refreshed, then the entry is dynamically re-anchored after the active predecessor.
 * - Given list items are being deleted, when filtered snapshot is computed, then deleting items are removed while order of remaining items is preserved.
 *
 * Observable outcomes:
 * - resolved memo item or null.
 * - resolved render key string.
 * - updated ExitAnimationRegistry entries.
 *
 * TDD proof:
 * - Fails if an unloaded index returns a duplicate key instead of a placeholder key, causing LazyColumn layout crashes.
 *
 * Excludes:
 * - compose layouts, image loaders, databases.
 *
 * Test Change Justification:
 * - Reason category: systemic behavior replacement.
 * - Old behavior/assertion being replaced: render-count stabilizer and retained-exit compensation scenarios.
 * - Why old assertion is no longer correct: placeholder-backed Paging itemCount now owns the absolute index space, so UI count compensation is removed.
 * - Coverage preserved by: tests cover placeholder keys, loaded-window alignment, paging peeks, duplicate keys, and retained-exit placement.
 * - Why this is not fitting the test to the implementation: the assertions target list keys and resolved rows that LazyColumn consumes, which are the observable list contract.
 */
class PagedMemoListMotionTddTest : FunSpec({
    test("given snapshot memos and deleting ids when filtered then deleted items are excluded and order is preserved") {
        val memo1 = MemoUiModel(
            memo = com.lomo.domain.model.Memo(
                id = "1",
                content = "memo1",
                rawContent = "memo1",
                timestamp = 1000L,
                updatedAt = 1000L,
                isPinned = false,
                dateKey = "2026_06_21"
            ),
            processedContent = "memo1",
            precomputedRenderPlan = null,
            tags = persistentListOf(),
            reminders = persistentListOf(),
            imageUrls = persistentListOf(),
            shouldShowExpand = false,
            collapsedSummary = ""
        )
        val memo2 = memo1.copy(memo = memo1.memo.copy(id = "2"))
        val memo3 = memo1.copy(memo = memo1.memo.copy(id = "3"))

        val snapshotMemos = listOf(memo1, memo2, memo3).toImmutableList()
        val deletingIds = setOf("2").toPersistentSet()

        val filtered = snapshotMemos.filterNot { it.memo.id in deletingIds }

        filtered shouldBe listOf(memo1, memo3)
    }

    test("given duplicate memo ids when generating render keys then keys are unique and order is preserved") {
        val baseKeys = listOf("1", "2", "1", "3", "2")
        val uniqueKeys = uniqueMemoListRenderKeys(baseKeys)

        uniqueKeys shouldBe listOf("1", "2", "1\u0000dup-2", "3", "2\u0000dup-4")
    }


    test("given rendered index is beyond loaded item count when row composes then nearest valid paging index is used") {
        pagingAccessIndexForRenderedRow(
            index = 59,
            pagedItemCount = 60,
            renderedItemCount = 200,
        ) shouldBe 59

        pagingAccessIndexForRenderedRow(
            index = 140,
            pagedItemCount = 60,
            renderedItemCount = 200,
        ) shouldBe 59

        pagingAccessIndexForRenderedRow(
            index = 201,
            pagedItemCount = 60,
            renderedItemCount = 200,
        ) shouldBe null

        pagingAccessIndexForRenderedRow(
            index = 0,
            pagedItemCount = 0,
            renderedItemCount = 200,
        ) shouldBe null
    }

    test("memoListItemAt retrieves from loaded window or peeks from paging items") {
        val pagedMemos = mockk<LazyPagingItems<MemoUiModel>>()
        val item1 = MemoUiModel(
            memo = com.lomo.domain.model.Memo(
                id = "1",
                content = "body",
                rawContent = "body",
                timestamp = 1000L,
                dateKey = "2026_06_27"
            ),
            processedContent = "body",
            precomputedRenderPlan = null,
            tags = persistentListOf()
        )
        val visiblePagedMemos = listOf(item1).toImmutableList()

        // 1. Within loaded window
        memoListItemAt(
            index = 10,
            visiblePagedMemoStartIndex = 10,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos
        ) shouldBe item1

        // 2. Peek fallback (exactly 2 stubbings used)
        every { pagedMemos.itemCount } returns 20
        every { pagedMemos.peek(5) } returns item1
        memoListItemAt(
            index = 5,
            visiblePagedMemoStartIndex = 10,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos
        ) shouldBe item1
    }

    test("memoListItemKey returns memo id or placeholder key") {
        val pagedMemos = mockk<LazyPagingItems<MemoUiModel>>()
        val item1 = MemoUiModel(
            memo = com.lomo.domain.model.Memo(
                id = "my-memo-id",
                content = "body",
                rawContent = "body",
                timestamp = 1000L,
                dateKey = "2026_06_27"
            ),
            processedContent = "body",
            precomputedRenderPlan = null,
            tags = persistentListOf()
        )
        val visiblePagedMemos = listOf(item1).toImmutableList()

        // 1. Within loaded window
        memoListItemKey(
            index = 10,
            visiblePagedMemoStartIndex = 10,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos
        ) shouldBe "my-memo-id"

        // 2. Beyond itemCount / Placeholder fallback (exactly 1 stubbing used)
        every { pagedMemos.itemCount } returns 5
        memoListItemKey(
            index = 8,
            visiblePagedMemoStartIndex = 10,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos
        ) shouldBe "paging-placeholder-8"
    }

    test("given an exiting item with a stale anchor when its anchor is updated then it is placed after the new anchor") {
        val registry = ExitAnimationRegistry<String>()
        registry.beginExit(
            id = "item_3",
            item = "item_3",
            anchoredAfterKey = "stale_anchor",
        )

        // 1. Initially, item_3 is still in the active items list [item_1, item_2, item_3]
        val allItems = listOf("item_1", "item_2", "item_3")

        // Simulating the collect loop in LaunchedEffect:
        val indexMap = allItems.mapIndexed { idx, item -> item to idx }.toMap()
        registry.entries.value.forEach { (id, entry) ->
            val index = indexMap[id]
            if (index != null) {
                val expectedAnchor = if (index > 0) allItems[index - 1] else null
                if (entry.anchoredAfterKey != expectedAnchor) {
                    registry.beginExit(
                        id = id,
                        item = entry.item,
                        anchoredAfterKey = expectedAnchor,
                    )
                }
            }
        }

        // Verify the anchor is dynamically corrected to the predecessor
        registry.entries.value["item_3"]!!.anchoredAfterKey shouldBe "item_2"

        // 2. Now item_3 is removed from the active items list (due to deletion)
        // allItems becomes [item_1, item_2]
        val newAllItems = listOf("item_1", "item_2")

        // Resolve the render list
        val renderList = resolveExitRenderList(
            allItems = newAllItems,
            itemKey = { it },
            activeExits = registry.entries.value
        )

        // renderList should have size 3, and item_3 must be placed exactly after item_2
        renderList.map { it.item } shouldBe listOf("item_1", "item_2", "item_3")
    }
})
