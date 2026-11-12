package com.lomo.ui.component.common

import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: select at most one quick-ball action per touch sequence; owning layer: ui-components;
 * priority: P1.
 * Scenarios:
 * - Given a stationary touch, when released before/after the timeout, then tap/long press is returned.
 * - Given a long-press candidate, when dragged and released, then only the swipe is committed.
 * - Given a swipe, when returned to the center or diagonal, then release does not become a tap.
 * - Given cancellation or a completed sequence, when released again, then no action is returned.
 * Observable outcomes: preview candidates and the single committed gesture.
 * TDD proof: companion domain QuickBallConfigTest RED failed with NoSuchElementException for the
 * missing quick-ball descriptor; this state machine covers gesture scenarios in the GREEN suite.
 * GREEN: ./kotlin test --include-module=app --include-module=data --include-module=domain
 * --include-module=ui-components; all 2598 discovered tests passed (including both domain targets),
 * evidence: /tmp/lomo-quick-ball-final-tests.stdout.
 * Excludes: Compose pointer delivery, layout, haptic hardware and Android permissions.
 */
class GestureBallSessionTest : UiComponentsFunSpec() {
    init {
        test("given a short stationary touch when released then one tap is committed") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(2f, 3f, 100L) shouldBe null
            session.release() shouldBe QuickBallGesture.TAP
            session.release() shouldBe null
        }

        test("given a held touch when swiped after long press then only the swipe is committed") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(0f, 0f, 500L) shouldBe QuickBallGesture.LONG_PRESS
            session.update(0f, -30f, 600L) shouldBe QuickBallGesture.UP
            session.release() shouldBe QuickBallGesture.UP
            session.release() shouldBe null
        }

        test("given a held touch when released then long press is committed") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(0f, 0f, 500L)
            session.release() shouldBe QuickBallGesture.LONG_PRESS
        }

        test("given swipes in four directions when released then the dominant direction is committed") {
            listOf(
                Triple(0f, -30f, QuickBallGesture.UP),
                Triple(0f, 30f, QuickBallGesture.DOWN),
                Triple(-30f, 2f, QuickBallGesture.LEFT),
                Triple(30f, 2f, QuickBallGesture.RIGHT),
            ).forEach { (x, y, expected) ->
                val session = GestureBallSession(8f, 24f, 500L)
                session.update(x, y, 100L) shouldBe expected
                session.release() shouldBe expected
            }
        }

        test("given a swipe returned to center when released then the action is canceled") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(30f, 0f, 100L)
            session.update(0f, 0f, 600L) shouldBe null
            session.release() shouldBe null
        }

        test("given diagonal or subthreshold movement when released then no action is guessed") {
            listOf(30f to 30f, 15f to 0f).forEach { (x, y) ->
                val session = GestureBallSession(8f, 24f, 500L)
                session.update(x, y, 600L) shouldBe null
                session.release() shouldBe null
            }
        }

        test("given a canceled touch when released then no action is committed") {
            val session = GestureBallSession(8f, 24f, 500L)
            session.update(0f, -30f, 100L)
            session.cancel()
            session.release() shouldBe null
        }
    }
}
