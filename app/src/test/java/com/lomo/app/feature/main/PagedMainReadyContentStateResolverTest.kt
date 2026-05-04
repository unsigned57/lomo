package com.lomo.app.feature.main

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: resolvePagedMainReadyContentState
 * - Behavior focus: main-screen ready-state rendering when the default feed is driven by Paging3.
 * - Observable outcomes: derived ready-content branch selection for initial paging refresh, empty, and list.
 * - Red phase: Fails before the fix because the paged ready-state resolver treats an unloaded
 *   paging refresh as a full-screen Loading state, so a retained root can sit behind a loading
 *   surface even though no root switch, workspace rebuild, or import is happening.
 * - Excludes: Compose rendering, pager configuration, append/prepend load states, and retry UX.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression correction.
 * - Old behavior/assertion being replaced: the previous tests allowed an itemCount=0 Paging refresh
 *   state to resolve to a full-screen Loading branch.
 * - Why the old assertion is no longer correct: once MainScreenState is Ready, Paging refresh is the
 *   first-page query path, not workspace initialization; blocking the whole screen defeats the paged
 *   feed startup contract.
 * - Coverage preserved by: MainViewModel still covers unresolved root and initial workspace import
 *   loading, while this resolver still distinguishes true empty directories from a list shell.
 * - Why this is not fitting the test to the implementation: this locks the user-visible contract
 *   that only real refresh loading should block the root's memo surface.
 */
class PagedMainReadyContentStateResolverTest {
    @Test
    fun `resolves list shell while initial paging refresh is in progress`() {
        assertEquals(
            MainReadyContentState.List,
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
    fun `resolves list shell for unloaded not-loading refresh that has not reached pagination end`() {
        assertEquals(
            MainReadyContentState.List,
            resolvePagedMainReadyContentState(
                itemCount = 0,
                refreshState = LoadState.NotLoading(endOfPaginationReached = false),
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
