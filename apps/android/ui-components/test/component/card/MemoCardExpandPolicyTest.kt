package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: shouldShowMemoCardExpand
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: decide expand affordance from store-projected document length, not preview
 *   string length.
 *
 * Scenarios:
 * - Given a short preview whose store char_count exceeds the expand threshold, when the policy
 *   runs, then expand is shown.
 * - Given a short preview without a projected count, when the policy runs, then expand is hidden.
 * - Given a long preview without a projected count, when the policy runs, then expand is shown.
 *
 * Observable outcomes: boolean expand affordance.
 *
 * TDD proof: Fails if expand is derived from preview length while projectedCharCount is present.
 *
 * Excludes: Compose card rendering and Full-snapshot loading.
 */
class MemoCardExpandPolicyTest : UiComponentsFunSpec() {
    init {
        test("projected char count above threshold shows expand for a short preview") {
            shouldShowMemoCardExpand(
                content = "short preview",
                projectedCharCount = 900L,
            ) shouldBe true
        }

        test("short preview without projected count does not show expand") {
            shouldShowMemoCardExpand(content = "short preview") shouldBe false
        }

        test("preview longer than the char threshold shows expand without a projected count") {
            shouldShowMemoCardExpand(content = "x".repeat(601)) shouldBe true
        }
    }
}
