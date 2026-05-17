package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: formatTypographyScalePercent
 * - Behavior focus: Typography slider scale-to-percent label formatting must round to the
 *   nearest integer so that values emitted by Compose Slider snap points (which contain
 *   float32 imprecision near multiples of 0.1) display the intended human percentage,
 *   not the truncated one-off.
 * - Observable outcomes: returned percent string.
 * - Red phase: Fails before the fix because the inline `(value * 100).toInt()` truncates
 *   values like 1.0999998f to "109%" instead of rounding to "110%".
 * - Excludes: Slider composable wiring, datastore persistence, preview card layout.
 */
class SettingsTypographyPercentFormatterTest : AppFunSpec() {
    init {
        test("integer scale formats as exact percent") {
            (formatTypographyScalePercent(1.0f)) shouldBe ("100%")
        }
    }

    init {
        test("value slightly below tick rounds to nearest percent") {
            (formatTypographyScalePercent(1.0999998f)) shouldBe ("110%")
        }
    }

    init {
        test("value slightly above tick rounds to nearest percent") {
            (formatTypographyScalePercent(1.1000001f)) shouldBe ("110%")
        }
    }

    init {
        test("range minimum formats as fifty percent") {
            (formatTypographyScalePercent(0.5f)) shouldBe ("50%")
        }
    }

    init {
        test("range maximum formats as three hundred percent") {
            (formatTypographyScalePercent(3.0f)) shouldBe ("300%")
        }
    }

}
