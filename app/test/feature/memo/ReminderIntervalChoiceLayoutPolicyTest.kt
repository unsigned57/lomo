package com.lomo.app.feature.memo

import androidx.compose.ui.unit.dp
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: reminder interval choice layout policy.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: reminder interval choices use one fixed-size visual spec so short and long labels
 *   do not render as differently sized controls.
 *
 * Scenarios:
 * - Given the interval selector is displayed, when its layout spec is resolved, then every choice
 *   uses the same width, height, and spacing.
 *
 * Observable outcomes:
 * - Returned ReminderIntervalChoiceLayoutSpec values.
 *
 * TDD proof:
 * - RED before implementation because the reminder dialog uses SegmentedButton weight/layout directly
 *   and has no fixed interval-choice spec.
 *
 * Excludes:
 * - Compose rendering, date/time picker behavior, token generation, and localization.
 */
class ReminderIntervalChoiceLayoutPolicyTest : AppFunSpec() {
    init {
        test("interval choices have one fixed visual size and spacing") {
            ReminderIntervalChoiceLayoutPolicy.spec() shouldBe
                ReminderIntervalChoiceLayoutSpec(
                    choiceWidth = 72.dp,
                    choiceHeight = 40.dp,
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                )
        }
    }
}
