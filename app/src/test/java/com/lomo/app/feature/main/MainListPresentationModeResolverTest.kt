package com.lomo.app.feature.main

import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: resolveMainListPresentationMode
 * - Behavior focus: when the main screen should switch from the legacy in-memory list pipeline to
 *   the Paging3-backed default feed.
 * - Observable outcomes: resolved presentation mode for default, search, sort, and date-filter inputs.
 * - Red phase: Fails before the fix because the resolver does not exist, so the Paging3/default-list
 *   routing contract is not implemented yet.
 * - Excludes: pager wiring, Compose rendering, repository SQL, and memo-ui mapping internals.
 */
class MainListPresentationModeResolverTest {
    @Test
    fun `uses paged default mode for empty query with inactive filter`() {
        assertEquals(
            MainListPresentationMode.PagedDefault,
            resolveMainListPresentationMode(
                query = "",
                filter = MemoListFilter(),
            ),
        )
    }

    @Test
    fun `uses filtered list mode when search query is present`() {
        assertEquals(
            MainListPresentationMode.FilteredList,
            resolveMainListPresentationMode(
                query = "meeting",
                filter = MemoListFilter(),
            ),
        )
    }

    @Test
    fun `uses filtered list mode when sort order changes`() {
        assertEquals(
            MainListPresentationMode.FilteredList,
            resolveMainListPresentationMode(
                query = "",
                filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
            ),
        )
    }

    @Test
    fun `uses filtered list mode when date range is active`() {
        assertEquals(
            MainListPresentationMode.FilteredList,
            resolveMainListPresentationMode(
                query = "",
                filter =
                    MemoListFilter(
                        startDate = LocalDate.of(2026, 4, 1),
                        endDate = LocalDate.of(2026, 4, 3),
                    ),
            ),
        )
    }
}
