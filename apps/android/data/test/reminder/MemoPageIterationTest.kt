package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: forEachStoreMemoPage.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: consume memo pages without materializing the whole repository result.
 *
 * Scenarios:
 * - Given full and short cursor pages, when iterating, then each page is consumed in order and
 *   the terminal page stops iteration.
 * - Given an empty first page, when iterating, then no later cursor is requested.
 *
 * Observable outcomes: requested cursors and consumed items.
 *
 * TDD proof: RED before implementation because the keyset iteration helper did not exist.
 *
 * Excludes:
 * - Repository persistence, reminder scheduling, and UI state.
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StorePageCursor

private fun summary(id: Int) =
    StoreMemoSummary(
        memoId = id.toString(),
        sourcePath = "$id.md",
        fileFingerprint = "a".repeat(64),
        updatedAtMs = 1,
        createdAtMs = 1,
        hasTodo = false,
        hasUrl = false,
        hasAttachment = false,
        isPinned = false,
        isTrashed = false,
        bodyPreview = "",
        contentRevision = 1,
    )

class MemoPageIterationTest : FunSpec({
    test("given full then short pages when iterating then pages are consumed in order") {
        val requested = mutableListOf<String?>()
        val consumed = mutableListOf<Int>()
        val first = StorePageCursor("cursor-1")

        forEachStoreMemoPage(
            pageSize = 2,
            loadPage = { cursor, _ ->
                requested += cursor?.encoded
                if (cursor == null) {
                    StoreMemoPage(
                        items = listOf(summary(1), summary(2)),
                        nextCursor = first,
                        highWaterRevision = 1,
                        queryFingerprint = "q",
                    )
                } else {
                    StoreMemoPage(
                        items = listOf(summary(3)),
                        nextCursor = null,
                        highWaterRevision = 1,
                        queryFingerprint = "q",
                    )
                }
            },
        ) { consumed += it.memoId.toInt() }

        requested shouldBe listOf(null, "cursor-1")
        consumed shouldBe listOf(1, 2, 3)
    }

    test("given an empty first page when iterating then no later page is requested") {
        val requestedCursors = mutableListOf<String?>()

        forEachStoreMemoPage(pageSize = 2, loadPage = { cursor, _ ->
            requestedCursors += cursor?.encoded
            StoreMemoPage(emptyList(), null, 1, "q")
        }) { error("empty page should not consume an item") }

        requestedCursors shouldBe listOf(null)
    }

})
