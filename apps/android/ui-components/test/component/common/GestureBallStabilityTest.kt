package com.lomo.ui.component.common

import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: stable directional feedback near the swipe threshold; owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given an armed swipe, when a finger jitters below the activation threshold, then it stays armed.
 * - Given an armed swipe, when returned near the center, then release cancels the action.
 * Observable outcomes: candidate and release gesture, including canceled release.
 * TDD proof: RED /tmp/lomo-ui-red.stdout: the armed UP candidate became null at 23px.
 * GREEN: ./kotlin test --include-module=app --include-module=domain --include-module=ui-components;
 * /tmp/lomo-ui-green.stdout, all 1799 tests passed across the four module targets.
 * Excludes: Android touch dispatch, rendered spring frames and physical haptics.
 */
class GestureBallStabilityTest : UiComponentsFunSpec() {
    init {
        test("given an armed swipe when jitter crosses the activation edge then feedback stays stable") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(0f, -26f, 100L) shouldBe QuickBallGesture.UP
            session.update(0f, -23f, 120L) shouldBe QuickBallGesture.UP
            session.release() shouldBe QuickBallGesture.UP
        }

        test("given an armed swipe when returned near center then release cancels") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(30f, 0f, 100L) shouldBe QuickBallGesture.RIGHT
            session.update(10f, 0f, 120L) shouldBe null
            session.release() shouldBe null
        }
    }
}
