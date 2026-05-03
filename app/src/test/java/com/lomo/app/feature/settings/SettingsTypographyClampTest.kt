package com.lomo.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

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
class SettingsTypographyClampTest {
    @Test
    fun `value below minimum coerces to minimum`() {
        assertEquals(0.5f, clampTypographyScale(0.4f))
    }

    @Test
    fun `value at minimum returns minimum`() {
        assertEquals(0.5f, clampTypographyScale(0.5f))
    }

    @Test
    fun `value above maximum coerces to maximum`() {
        assertEquals(3.0f, clampTypographyScale(3.5f))
    }

    @Test
    fun `value at maximum returns maximum`() {
        assertEquals(3.0f, clampTypographyScale(3.0f))
    }

    @Test
    fun `value within range is preserved without rounding`() {
        assertEquals(1.234f, clampTypographyScale(1.234f))
    }

    @Test
    fun `value just below minimum due to float drift coerces to minimum`() {
        assertEquals(0.5f, clampTypographyScale(0.49999997f))
    }
}
