package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: clampTypographyScale
 * - Behavior focus: The new -/+ stepper buttons drive the typography scale directly into
 *   the persistence pipeline (SettingsPreferenceRepositories.setFontSizeScale forwards to
 *   datastore unchecked). The UI is the only layer that can prevent an out-of-range write
 *   when the stepper is rapidly tapped at a bound or invoked with a float-drifted input.
 *   This test locks the in/out contract of the clamp helper that the stepper calls.
 * - Observable outcomes: returned scale Float.
 * - Red phase: Fails to compile before the helper is introduced because clampTypographyScale
 *   does not exist; a stepper that writes value - 0.05f from 0.5f would otherwise persist 0.45f.
 * - Excludes: Slider composable wiring, FilledTonalIconButton enabled-state, Card layout,
 *   datastore behavior.
 */
class SettingsTypographyClampTest : AppFunSpec() {
    init {
        test("value below minimum coerces to minimum") {
            (clampTypographyScale(0.4f)) shouldBe (0.5f)
        }
    }

    init {
        test("value at minimum returns minimum") {
            (clampTypographyScale(0.5f)) shouldBe (0.5f)
        }
    }

    init {
        test("value above maximum coerces to maximum") {
            (clampTypographyScale(3.5f)) shouldBe (3.0f)
        }
    }

    init {
        test("value at maximum returns maximum") {
            (clampTypographyScale(3.0f)) shouldBe (3.0f)
        }
    }

    init {
        test("value within range is preserved without rounding") {
            (clampTypographyScale(1.234f)) shouldBe (1.234f)
        }
    }

    init {
        test("value just below minimum due to float drift coerces to minimum") {
            (clampTypographyScale(0.49999997f)) shouldBe (0.5f)
        }
    }

}
