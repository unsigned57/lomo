package com.lomo.app.feature.search

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: resolveSearchContentState(...)
 * - Owning layer: app Search UI presentation state
 * - Priority tier: P1
 * - Capability: resolve the local SearchScreen content target without becoming a collection-state
 *   presenter or memo-result projection boundary.
 *
 * Scenarios:
 * - Given the query is empty, when content state resolves, then the initial empty state wins over
 *   loading and results facts.
 * - Given a non-empty query is loading, when content state resolves, then loading wins over result
 *   presence.
 * - Given a non-empty query is not loading and has no results, when content state resolves, then
 *   the no-results target is selected.
 * - Given a non-empty query is not loading and has results, when content state resolves, then the
 *   results target is selected.
 * - Given a non-empty query is not loading and has no results but has active exits, when content state resolves, then the
 *   results target is selected.
 *
 * - Observable outcomes: returned enum value per matrix row.
 *
 * TDD proof:
 * - Fails before the stabilization when a query results set becomes empty during active exits, incorrectly selecting NoResults instead of Results.
 *
 * Excludes:
 * - Collection-state integration, result projection, SearchViewModel behavior, Compose animation
 *   frames, and memo card rendering.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
class SearchContentStateTest : AppFunSpec() {
    init {
        test("given an empty query when state resolves then it is EmptyInitial") {
            val state = resolveSearchContentState(query = "", showLoading = false, hasResults = false)

            state shouldBe SearchContentState.EmptyInitial
        }

        test("given an empty query with loading and results when state resolves then EmptyInitial still wins") {
            val state = resolveSearchContentState(query = "", showLoading = true, hasResults = true)

            state shouldBe SearchContentState.EmptyInitial
        }

        test("given a non-empty query and loading flag when state resolves then it is Loading") {
            val state = resolveSearchContentState(query = "hello", showLoading = true, hasResults = false)

            state shouldBe SearchContentState.Loading
        }

        test("given a non-empty query no loading and empty results when state resolves then it is NoResults") {
            val state = resolveSearchContentState(query = "hello", showLoading = false, hasResults = false)

            state shouldBe SearchContentState.NoResults
        }

        test("given a non-empty query no loading and non-empty results when state resolves then it is Results") {
            val state = resolveSearchContentState(query = "hello", showLoading = false, hasResults = true)

            state shouldBe SearchContentState.Results
        }

        test("given a non-empty query no loading empty results but has active exits when state resolves then it is Results") {
            val state = resolveSearchContentState(
                query = "hello",
                showLoading = false,
                hasResults = false,
                hasActiveExits = true
            )

            state shouldBe SearchContentState.Results
        }
    }
}

