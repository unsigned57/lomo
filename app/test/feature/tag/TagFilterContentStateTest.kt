/*
 * Behavior Contract:
 * - Unit under test: resolveTagFilterContentState
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: distinguish tag paging progress, errors, empty results, and loaded results.
 *
 * Scenarios:
 * - Given no items and an in-flight refresh, when the tag state is resolved, then loading is shown.
 * - Given no items and a failed refresh, when the tag state is resolved, then the error affordance is shown.
 * - Given a completed refresh with no items, when the tag state is resolved, then the empty state is shown.
 * - Given paging has not reached its end and no items are loaded, when resolved, then loading remains visible.
 * - Given loaded items, when the tag state is resolved, then the memo list is shown.
 *
 * Observable outcomes:
 * - The user-visible content state enum.
 *
 * TDD proof:
 * - RED before the fix: the screen treated every zero-item state as empty and hid loading/errors.
 *
 * Excludes:
 * - Compose rendering, query implementation, and navigation.
 */

package com.lomo.app.feature.tag

import androidx.paging.LoadState
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class TagFilterContentStateTest : AppFunSpec() {
    init {
        test("given an initial refresh in progress then loading is shown") {
            resolveTagFilterContentState(0, LoadState.Loading) shouldBe TagFilterContentState.Loading
        }

        test("given an initial refresh failure then the error state is shown") {
            resolveTagFilterContentState(0, LoadState.Error(IllegalStateException("query failed"))) shouldBe
                TagFilterContentState.Error
        }

        test("given a completed empty refresh then the empty state is shown") {
            resolveTagFilterContentState(0, LoadState.NotLoading(endOfPaginationReached = true)) shouldBe
                TagFilterContentState.Empty
        }

        test("given an unresolved not-loading refresh then loading remains visible") {
            resolveTagFilterContentState(0, LoadState.NotLoading(endOfPaginationReached = false)) shouldBe
                TagFilterContentState.Loading
        }

        test("given loaded items then the list is shown") {
            resolveTagFilterContentState(1, LoadState.NotLoading(endOfPaginationReached = false)) shouldBe
                TagFilterContentState.List
        }
    }
}
