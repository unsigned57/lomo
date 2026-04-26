package com.lomo.app.feature.main

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: resolvePagedMainReadyContentState
 * - Behavior focus: main-screen ready-state rendering when the default feed is driven by Paging3.
 * - Observable outcomes: derived ready-content branch selection for initial loading, empty, and list.
 * - Red phase: Fails before the fix because the paged ready-state resolver does not exist, so the
 *   Paging3 feed cannot distinguish loading from true empty state.
 * - Excludes: Compose rendering, pager configuration, append/prepend load states, and retry UX.
 */
class PagedMainReadyContentStateResolverTest {
    @Test
    fun `resolves loading while refresh is in progress and no items are loaded`() {
        assertEquals(
            MainReadyContentState.Loading,
            resolvePagedMainReadyContentState(
                itemCount = 0,
                refreshState = LoadState.Loading,
            ),
        )
    }

    @Test
    fun `resolves empty when refresh completed without any items`() {
        assertEquals(
            MainReadyContentState.Empty,
            resolvePagedMainReadyContentState(
                itemCount = 0,
                refreshState = LoadState.NotLoading(endOfPaginationReached = true),
            ),
        )
    }

    @Test
    fun `resolves list when at least one item is loaded`() {
        assertEquals(
            MainReadyContentState.List,
            resolvePagedMainReadyContentState(
                itemCount = 1,
                refreshState = LoadState.NotLoading(endOfPaginationReached = false),
            ),
        )
    }
}
