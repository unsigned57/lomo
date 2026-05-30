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
 *
 * - Observable outcomes: returned enum value per matrix row.
 *
 * TDD proof:
 * - Fails before the stabilization because resolveSearchContentState still accepts Search's
 *   result list, making this local display policy look like a Batch B collection-state/projection
 *   integration point instead of a primitive local presentation mapping.
 *
 * Excludes:
 * - Collection-state integration, result projection, SearchViewModel behavior, Compose animation
 *   frames, and memo card rendering.
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
    }
}
