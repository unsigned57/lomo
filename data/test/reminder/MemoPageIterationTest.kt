package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: forEachMemoPage.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: consume memo pages without materializing the whole repository result.
 *
 * Scenarios:
 * - Given full and short pages, when iterating, then each page is consumed in order and the
 *   short page terminates iteration.
 * - Given an empty first page, when iterating, then no later offset is requested.
 *
 * Observable outcomes: requested offsets and consumed items.
 *
 * TDD proof: RED before implementation because forEachMemoPage did not exist.
 *
 * Excludes:
 * - Repository persistence, reminder scheduling, and UI state.
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoPageIterationTest : FunSpec({
    test("given full then short pages when iterating then pages are consumed in order") {
        val requested = mutableListOf<Pair<Int, Int>>()
        val consumed = mutableListOf<Int>()
        val pages = mapOf(
            0 to listOf(1, 2),
            2 to listOf(3),
        )

        forEachMemoPage(pageSize = 2, loadPage = { limit, offset ->
            requested += limit to offset
            pages[offset].orEmpty()
        }) { consumed += it }

        requested shouldBe listOf(2 to 0, 2 to 2)
        consumed shouldBe listOf(1, 2, 3)
    }

    test("given an empty first page when iterating then no later page is requested") {
        val requestedOffsets = mutableListOf<Int>()

        forEachMemoPage<Int>(pageSize = 2, loadPage = { _, offset ->
            requestedOffsets += offset
            emptyList<Int>()
        }) { error("empty page should not consume an item") }

        requestedOffsets shouldBe listOf(0)
    }
})
