package com.lomo.ui.text

import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: platform text selection-handle styling contract in ui-components.
 * - Behavior focus: tinted handle copies must preserve the original drawable's directional and
 *   anchor-related state so coloring the handles does not shift the visible start/end selection affordances.
 * - Observable outcomes: source-level preservation of bounds, layout direction, drawable state,
 *   level, auto-mirroring, and hotspot bounds in PlatformTextSelectionHandleStyling.kt.
 * - Red phase: Fails before the fix because tintedCopy() only clones and tints the drawable,
 *   dropping the original state that anchors left/right selection handles correctly.
 * - Excludes: OEM artwork differences, actual drag gestures, and action-mode UI.
 */
class PlatformTextSelectionHandleStylingContractTest {
    private val sourceText: String by lazy {
        Paths
            .get("src/main/java/com/lomo/ui/text/PlatformTextSelectionHandleStyling.kt")
            .toFile()
            .readText()
    }

    @Test
    fun `tinted handle copies preserve directional and drawable state`() {
        assertTrue(sourceText.contains("copyBounds()"))
        assertTrue(sourceText.contains("layoutDirection = originalLayoutDirection"))
        assertTrue(sourceText.contains("state = originalState"))
        assertTrue(sourceText.contains("level = originalLevel"))
        assertTrue(sourceText.contains("isAutoMirrored = originalAutoMirrored"))
    }

    @Test
    fun `tinted handle copies preserve hotspot bounds`() {
        assertTrue(sourceText.contains("getHotspotBounds("))
        assertTrue(sourceText.contains("setHotspotBounds("))
    }
}
