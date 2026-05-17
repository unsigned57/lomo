package com.lomo.app.feature.main

import androidx.paging.LoadState
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

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
class PagedMainReadyContentStateResolverTest : AppFunSpec() {
    init {
        test("resolves list shell while initial paging refresh is in progress") {
            (resolvePagedMainReadyContentState(
                    itemCount = 0,
                    refreshState = LoadState.Loading,
                )) shouldBe (MainReadyContentState.List)
        }
    }

    init {
        test("resolves empty when refresh completed without any items") {
            (resolvePagedMainReadyContentState(
                    itemCount = 0,
                    refreshState = LoadState.NotLoading(endOfPaginationReached = true),
                )) shouldBe (MainReadyContentState.Empty)
        }
    }

    init {
        test("resolves list shell for unloaded not-loading refresh that has not reached pagination end") {
            (resolvePagedMainReadyContentState(
                    itemCount = 0,
                    refreshState = LoadState.NotLoading(endOfPaginationReached = false),
                )) shouldBe (MainReadyContentState.List)
        }
    }

    init {
        test("resolves list when at least one item is loaded") {
            (resolvePagedMainReadyContentState(
                    itemCount = 1,
                    refreshState = LoadState.NotLoading(endOfPaginationReached = false),
                )) shouldBe (MainReadyContentState.List)
        }
    }

}
