/*
 * Behavior Contract:
 * Capability: a collapsed text card never presents more than the collapsed body budget, even while
 * its body height is still unmeasured; owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given an unmeasured text body, when the card is collapsed, then the clamp applies.
 * - Given a collapsed text body whose measured height fits, then the clamp still applies.
 * - Given a body the user expanded, when composed, then no clamp applies.
 * - Given an image body, when composed, then media stays unfolded and no clamp applies.
 * Observable outcomes: the clamp decision that drives the body height constraint.
 * TDD proof: ./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.component.card.MemoCardBodyClampPolicyTest'
 * RED: with the clamp still derived from the measured overflow, an unmeasured text body returned
 * false; evidence: target/lomo/reports/manual/card-clamp/clamp-red.stdout.log.
 * GREEN: target/lomo/reports/manual/card-clamp/clamp-green.stdout.log after the clamp stopped
 * depending on the measured overflow.
 * Excludes: Compose measurement frames and the expand affordance decision.
 * Production scope: apps/android/ui-components/src/component/card/MemoCardSummaryUtils.kt
 * Production scope: apps/android/ui-components/src/component/card/MemoCard.kt
 */
package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MemoCardBodyClampPolicyTest : UiComponentsFunSpec() {
    init {
        test("given an unmeasured text body when the card is collapsed then the clamp applies") {
            shouldClampMemoCardBody(requestedExpanded = false, hasImages = false) shouldBe true
        }
        test("given a collapsed text body whose measured height fits then the clamp still applies") {
            shouldClampMemoCardBody(requestedExpanded = false, hasImages = false) shouldBe true
        }
        test("given a body the user expanded when composed then no clamp applies") {
            shouldClampMemoCardBody(requestedExpanded = true, hasImages = false) shouldBe false
        }
        test("given an image body when composed then media stays unfolded and no clamp applies") {
            shouldClampMemoCardBody(requestedExpanded = false, hasImages = true) shouldBe false
        }
    }
}
