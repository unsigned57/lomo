package com.lomo.app.feature.main

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import com.lomo.ui.component.common.uniqueMemoListRenderKeys

/*
 * Behavior Contract:
 * - Unit under test: PagedMemoListMotionTddTest, PagedMemoListContentSupport
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: verify render list filtration, duplicate key resolution, list motion specs, and paging render item count calculation.
 *
 * Scenarios:
 * - Given a snapshot of memo models and a set of deleting memo IDs, when filtered, then render list excludes deleting memo IDs and preserves ordering.
 * - Given duplicate memo IDs, when generating render keys, then keys are unique and order is preserved.
 * - Given paged memos and total count, when computeRenderedItemCount is called, then it returns count bounded by knownTotalItemCount (never exceeding it even when pagedMemosItemCount is temporarily larger).
 * - Given active exits, when paging refreshes, then the rendered item count remains stable and includes retained exits.
 *
 * Observable outcomes:
 * - filtered list size and items, unique keys, correct render item count calculation.
 *
 * TDD proof:
 * - Fails before the stabilization when paging refreshes during active exits, causing the rendered item count to shrink before the exits settle.
 *
 * Excludes:
 * - Compose layout rendering, database persistence, networks.
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

    test("given paged memos and total count when computeRenderedItemCount is called then it returns count bounded by knownTotalItemCount") {
        computeRenderedItemCount(
            snapshotStartIndex = 0,
            visiblePagedMemosSize = 20,
            pagedMemosItemCount = 20,
            knownTotalItemCount = 100,
            pageSize = 20
        ) shouldBe 40

        computeRenderedItemCount(
            snapshotStartIndex = 0,
            visiblePagedMemosSize = 20,
            pagedMemosItemCount = 20,
            knownTotalItemCount = 30,
            pageSize = 20
        ) shouldBe 30

        computeRenderedItemCount(
            snapshotStartIndex = 0,
            visiblePagedMemosSize = 22,
            pagedMemosItemCount = 20,
            knownTotalItemCount = 100,
            pageSize = 20
        ) shouldBe 40

        computeRenderedItemCount(
            snapshotStartIndex = 0,
            visiblePagedMemosSize = 20,
            pagedMemosItemCount = 100,
            knownTotalItemCount = 80,
            pageSize = 20
        ) shouldBe 80
    }

    test("given active exits when paging refreshes then the rendered item count remains stable and includes retained exits") {
        val snapshotStartIndex = 0
        val pageSize = 20

        // Initial state: N = 3 items, 0 active exits, 0 retained exits
        val initialRenderedCount = computeRenderedItemCount(
            snapshotStartIndex = snapshotStartIndex,
            visiblePagedMemosSize = 3,
            pagedMemosItemCount = 3,
            knownTotalItemCount = 3,
            pageSize = pageSize
        )
        initialRenderedCount shouldBe 3

        // Phase 1: delete item, begin exit (still in paging snapshot)
        val deletingIds = setOf("item_2")
        val snapshotMemosPhase1 = setOf("item_1", "item_2", "item_3")
        val retainedExitsCountPhase1 = computeRetainedExitsCount(deletingIds, snapshotMemosPhase1)
        retainedExitsCountPhase1 shouldBe 0
        
        val phase1RenderedCount = computeRenderedItemCount(
            snapshotStartIndex = snapshotStartIndex,
            visiblePagedMemosSize = 3, // renderList still has size 3
            pagedMemosItemCount = 3 + retainedExitsCountPhase1,
            knownTotalItemCount = 3 + retainedExitsCountPhase1,
            pageSize = pageSize
        )
        phase1RenderedCount shouldBe 3

        // Phase 2: DB deletion succeeds, paging invalidates, item_2 removed from paging snapshot
        val snapshotMemosPhase2 = setOf("item_1", "item_3")
        val retainedExitsCountPhase2 = computeRetainedExitsCount(deletingIds, snapshotMemosPhase2)
        retainedExitsCountPhase2 shouldBe 1 // retained because it is in deletingIds but not in paging snapshot
        
        val phase2RenderedCount = computeRenderedItemCount(
            snapshotStartIndex = snapshotStartIndex,
            visiblePagedMemosSize = 3, // renderList size is 3 (2 from snapshot + 1 retained exit)
            pagedMemosItemCount = 2 + retainedExitsCountPhase2,
            knownTotalItemCount = 2 + retainedExitsCountPhase2,
            pageSize = pageSize
        )
        phase2RenderedCount shouldBe 3 // should be stable!

        // Phase 3: exit settled, deleted item settled and removed from registry
        val deletingIdsPhase3 = emptySet<String>()
        val retainedExitsCountPhase3 = computeRetainedExitsCount(deletingIdsPhase3, snapshotMemosPhase2)
        retainedExitsCountPhase3 shouldBe 0

        val phase3RenderedCount = computeRenderedItemCount(
            snapshotStartIndex = snapshotStartIndex,
            visiblePagedMemosSize = 2, // renderList size is 2
            pagedMemosItemCount = 2 + retainedExitsCountPhase3,
            knownTotalItemCount = 2 + retainedExitsCountPhase3,
            pageSize = pageSize
        )
        phase3RenderedCount shouldBe 2 // drops to 2
    }
})

