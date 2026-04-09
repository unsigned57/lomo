package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet unified long-form motion state contract.
 * - Behavior focus: compact and long-form transitions must be driven by one shared motion-stage state machine instead of mixing a raw expanded boolean with a second surface transition flag.
 * - Observable outcomes: InputSheetComponents declares a dedicated InputSheetMotionStage state machine and no longer exposes the sheet body callback as a raw expanded boolean or derives a separate surfaceVisualExpanded flag from a second mode source.
 * - Red phase: Fails before the fix because InputSheet still drives content with a Boolean expanded callback while also keeping a second surface mode enum, so expand/collapse are not truly unified.
 * - Excludes: runtime animation timing, device IME behavior, and memo business logic.
 */
class InputSheetUnifiedMotionContractTest {
    private val componentsSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()
    private val motionStateSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetMotionState.kt").readText()

    @Test
    fun `input sheet uses a single motion stage state machine`() {
        assertTrue(
            "InputSheet should declare a single motion-stage enum that covers compact, expanding, expanded, and collapsing states.",
            motionStateSourceText.contains("enum class InputSheetMotionStage"),
        )
        assertFalse(
            "Passing a raw Boolean expanded flag into the content host keeps a second UI driver alive alongside the motion state machine.",
            componentsSourceText.contains("content: @Composable (Boolean, Modifier) -> Unit"),
        )
        assertFalse(
            "Deriving a second surfaceVisualExpanded Boolean means the sheet still has parallel motion state instead of one canonical stage.",
            componentsSourceText.contains("val surfaceVisualExpanded ="),
        )
    }
}
