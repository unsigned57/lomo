package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: startup memo-image preload candidate planner.
 * - Behavior focus: cold-start preload must warm image URLs from the first on-screen memo slice before scroll-driven viewport signals arrive, while keeping memo order and respecting the configured memo budget.
 * - Observable outcomes: selected preload URL list for startup memo slices.
 * - Red phase: Fails before the fix because MemoListContent has no startup-specific preload planner, so app entry waits for a later viewport pass before first-screen memo images start warming.
 * - Excludes: Coil request execution, LazyColumn measurement timing, and image decoding latency.
 */
class StartupImagePreloadPlannerTest {
    @Test
    fun `startup preload includes image urls from the first memo slice in order`() {
        val candidates =
            buildStartupImagePreloadCandidates(
                memos =
                    listOf(
                        memoUiModel("memo-1", "cover-1", "cover-2"),
                        memoUiModel("memo-2"),
                        memoUiModel("memo-3", "cover-3"),
                    ).toImmutableList(),
                startupMemoCount = 3,
            )

        assertEquals(listOf("cover-1", "cover-2", "cover-3"), candidates)
    }

    @Test
    fun `startup preload respects the configured memo budget`() {
        val candidates =
            buildStartupImagePreloadCandidates(
                memos =
                    listOf(
                        memoUiModel("memo-1", "cover-1"),
                        memoUiModel("memo-2", "cover-2"),
                        memoUiModel("memo-3", "cover-3"),
                    ).toImmutableList(),
                startupMemoCount = 2,
            )

        assertEquals(listOf("cover-1", "cover-2"), candidates)
    }

    private fun memoUiModel(
        id: String,
        vararg imageUrls: String,
    ): MemoUiModel =
        MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp = 1L,
                    content = id,
                    rawContent = id,
                    dateKey = "2026_04_02",
                ),
            processedContent = id,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
            imageUrls = persistentListOf(*imageUrls),
        )
}
