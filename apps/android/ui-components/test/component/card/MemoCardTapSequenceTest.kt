package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: immediate single-tap expansion and non-conflicting double-tap editing; UI; P1.
 * Scenarios:
 * Given a collapsed card, when the first tap releases, then it expands immediately.
 * Given a second release in the system double-tap window, then editing replaces a second toggle.
 * Given a timeout or interrupted sequence, then the next release is a new click.
 * Given an invalid clock or timing range, then the boundary rejects it.
 * Observable outcomes: expansion and editing state after each release, including time boundaries.
 * TDD proof: device RED/GREEN in reports/manual/memo-startup/memo-tap-*.log;
 * companion unit scenarios run through MemoCardTapSequenceTest.
 * Excludes: pixel motion and link/text-selection gesture dispatch.
 */
class MemoCardTapSequenceTest : UiComponentsFunSpec() {
    init {
        test("given two quick releases when tapping then expansion starts immediately and editing does not collapse") {
            val sequence = MemoCardTapSequence(40, 300)
            var expanded = false
            var editing = false
            fun release(at: Long) {
                when (sequence.release(at)) {
                    MemoCardTap.Click -> expanded = !expanded
                    MemoCardTap.Edit -> editing = true
                }
            }
            release(1000)
            expanded shouldBe true
            editing shouldBe false
            release(1100)
            expanded shouldBe true
            editing shouldBe true
            release(1200)
            expanded shouldBe false
        }

        test("given tap boundaries when releases arrive then only the system double-tap window edits") {
            for ((interval, expected) in listOf(
                39L to MemoCardTap.Click,
                40L to MemoCardTap.Edit,
                300L to MemoCardTap.Edit,
                301L to MemoCardTap.Click,
            )) {
                val sequence = MemoCardTapSequence(40, 300)
                sequence.release(1000) shouldBe MemoCardTap.Click
                sequence.release(1000 + interval) shouldBe expected
            }
        }

        test("given a long press or footer action when the sequence resets then the next tap clicks") {
            val sequence = MemoCardTapSequence(40, 300)
            sequence.release(1000) shouldBe MemoCardTap.Click
            sequence.reset()
            sequence.release(1100) shouldBe MemoCardTap.Click
        }

        test("given invalid timing when received then the gesture boundary rejects it") {
            shouldThrow<IllegalArgumentException> { MemoCardTapSequence(-1, 300) }
            shouldThrow<IllegalArgumentException> { MemoCardTapSequence(301, 300) }
            val sequence = MemoCardTapSequence(40, 300)
            sequence.release(1000)
            shouldThrow<IllegalArgumentException> { sequence.release(999) }
        }
    }
}
