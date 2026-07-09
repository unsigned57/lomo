/*
 * Behavior Contract:
 * - Capability: SecondsWheelMath governs how a snapped vertical wheel maps a
 *   LazyListState (firstVisibleItemIndex + offset) to a "seconds" value in 0..59,
 *   and how out-of-range integer inputs are clamped.
 * - Scenarios:
 *     Given a list snapped at index 12 with zero offset, when the centered second
 *     is requested, then it returns 12.
 *     Given a list at index 12 with a positive offset more than half itemHeight,
 *     when the centered second is requested, then it returns 13 (rounded toward
 *     the next visible item).
 *     Given a list at index 12 with a positive offset less than half itemHeight,
 *     when the centered second is requested, then it stays at 12.
 *     Given a clamp input outside 0..59, when clamped, then it is bound to the
 *     nearest endpoint (no wrap-around).
 * - Observable outcomes: pure integer returns from `SecondsWheelMath.centeredSecond`
 *   and `SecondsWheelMath.clamp` — proves the wheel will not wrap 60 → 0 and that
 *   snap rounding matches the visual "closest item" expectation.
 * - Excludes: Compose runtime, gesture inertia, LazyListState internals.
 */
package com.lomo.ui.component.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SecondsWheelMathTest : FunSpec({
    test("given snapped list at index 12 with zero offset when centered second requested then it returns 12") {
        SecondsWheelMath.centeredSecond(
            firstVisibleIndex = 12,
            firstVisibleOffsetPx = 0,
            itemHeightPx = 40,
        ) shouldBe 12
    }

    test("given offset greater than half item height when centered second requested then it rounds up") {
        SecondsWheelMath.centeredSecond(
            firstVisibleIndex = 12,
            firstVisibleOffsetPx = 25,
            itemHeightPx = 40,
        ) shouldBe 13
    }

    test("given offset less than half item height when centered second requested then it rounds down") {
        SecondsWheelMath.centeredSecond(
            firstVisibleIndex = 12,
            firstVisibleOffsetPx = 15,
            itemHeightPx = 40,
        ) shouldBe 12
    }

    test("given offset exactly half item height when centered second requested then it rounds up") {
        SecondsWheelMath.centeredSecond(
            firstVisibleIndex = 30,
            firstVisibleOffsetPx = 20,
            itemHeightPx = 40,
        ) shouldBe 31
    }

    test("given negative integer when clamped then it is bound to 0 without wrapping") {
        SecondsWheelMath.clamp(-3) shouldBe 0
    }

    test("given integer above the max when clamped then it is bound to 59 without wrapping") {
        SecondsWheelMath.clamp(63) shouldBe 59
    }

    test("given in-range integer when clamped then it is returned unchanged") {
        SecondsWheelMath.clamp(42) shouldBe 42
    }

    test("given a rounded index past the max when centered second requested then it is clamped to 59") {
        SecondsWheelMath.centeredSecond(
            firstVisibleIndex = 59,
            firstVisibleOffsetPx = 30,
            itemHeightPx = 40,
        ) shouldBe 59
    }
})
