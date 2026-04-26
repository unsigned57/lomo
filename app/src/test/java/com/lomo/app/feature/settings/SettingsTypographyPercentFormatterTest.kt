package com.lomo.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

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
class SettingsTypographyPercentFormatterTest {
    @Test
    fun `integer scale formats as exact percent`() {
        assertEquals("100%", formatTypographyScalePercent(1.0f))
    }

    @Test
    fun `value slightly below tick rounds to nearest percent`() {
        assertEquals("110%", formatTypographyScalePercent(1.0999998f))
    }

    @Test
    fun `value slightly above tick rounds to nearest percent`() {
        assertEquals("110%", formatTypographyScalePercent(1.1000001f))
    }

    @Test
    fun `range minimum formats as fifty percent`() {
        assertEquals("50%", formatTypographyScalePercent(0.5f))
    }

    @Test
    fun `range maximum formats as three hundred percent`() {
        assertEquals("300%", formatTypographyScalePercent(3.0f))
    }
}
