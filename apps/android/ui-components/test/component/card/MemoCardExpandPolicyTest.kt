/*
 * Behavior Contract:
 * Capability: expand only overflowing or upstream-truncated bodies; owner ui-components; P1.
 * Scenarios:
 * - Given a measured body fitting the viewport, when classified, then no fold control is shown.
 * - Given a body overflowing by one pixel, when classified, then expansion is available.
 * - Given hidden upstream content, when its short preview fits, then expansion remains available.
 * - Given an image body exceeding the text limit, when classified, then media stays unfolded.
 * Observable outcomes: expand affordance for measured heights and upstream truncation facts.
 * TDD proof: preview-red.log/preview-green.log protect empty-fold behavior; ui-green.json preserves device expansion.
 * Excludes: glyph measurement and full-snapshot fetching.
 * Media regression proof: ui-followup/media-red.log observes the old height-only classifier
 * folding media; the replacement adds the Rust-issued media fact to that same decision.
 * Test Change Justification:
 * Reason category: image cards must remain unfolded.
 * Old behavior/assertion being replaced: every body taller than 240 px is folded, regardless of media.
 * Why old assertion is no longer correct: the text height budget must not crop image content.
 * Coverage preserved by: original text fitting/overflow/truncation assertions and truncated-media reachability.
 * Why this is not fitting the test to the implementation: media-red.log records the unchanged false
 * expectation failing with the old API; the added hasImages input supplies the missing owner fact.
 * Test Change Justification:
 * Reason category: user-requested folding behavior change.
 * Old behavior/assertion being replaced: 600 source characters or 15 source lines imply overflow.
 * Why old assertion is no longer correct: markup length and source lines do not determine rendered height.
 * Coverage preserved by: measured fitting/overflow boundaries and the upstream truncation scenario, plus device creation and expansion flows.
 * Why this is not fitting the test to the implementation: the viewport and visible-content requirement determine the expected outcomes.
 */
package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MemoCardExpandPolicyTest : UiComponentsFunSpec() {
    init {
        test("given truncated media when classified then fetching full source remains available") {
            shouldShowMemoCardExpand(1000, 240, hasHiddenContent = true, hasImages = true) shouldBe true
        }
        test("given an image body when taller than the text limit then media remains unfolded") {
            shouldShowMemoCardExpand(1000, 240, hasHiddenContent = false, hasImages = true) shouldBe false
        }
        test("given fitting bodies when measured then expansion stays hidden") {
            listOf(0, 100, 240).forEach { height ->
                shouldShowMemoCardExpand(height, 240, hasHiddenContent = false, hasImages = false) shouldBe false
            }
        }
        test("given one pixel of overflow when measured then expansion is offered") {
            shouldShowMemoCardExpand(241, 240, hasHiddenContent = false, hasImages = false) shouldBe true
        }
        test("given an upstream truncated preview when it fits then full content remains reachable") {
            shouldShowMemoCardExpand(100, 240, hasHiddenContent = true, hasImages = false) shouldBe true
        }
    }
}
