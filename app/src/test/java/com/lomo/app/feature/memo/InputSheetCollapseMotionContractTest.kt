package com.lomo.app.feature.memo

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet collapse motion contract.
 * - Behavior focus: collapsing from long-form must keep one stable sheet surface, introduce an explicit collapsing path, and delay compact IME adaptation until the collapse motion completes.
 * - Observable outcomes: InputSheetComponents source declares a dedicated collapsing state or equivalent transition flag, does not switch the surface directly with AnimatedContent(targetState = isExpanded), and does not apply compact keyboard inset directly from the raw isExpanded false branch.
 * - Red phase: Fails before the fix because the sheet surface switches directly between expanded and compact shells, so the bottom anchor changes during collapse and leaves a visible gap above the keyboard.
 * - Excludes: device-specific IME animation timing, pixel rendering, and memo editor business logic.
 */
class InputSheetCollapseMotionContractTest {
    private val componentsSourceText =
        File("../ui-components/src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()
    private val motionStateSourceText =
        File("../ui-components/src/main/java/com/lomo/ui/component/input/InputSheetMotionState.kt").readText()

    @Test
    fun collapseMotion_usesDedicatedCollapsingStateInsteadOfDirectExpandedSwitch() {
        assertTrue(
            "InputSheet collapse should declare an explicit collapsing state or transition flag so the bottom anchor can stay pinned while the top edge moves down.",
            motionStateSourceText.contains("InputSheetMotionStage.Collapsing") ||
                motionStateSourceText.contains("enum class InputSheetMotionStage"),
        )
        assertFalse(
            "Driving the sheet surface directly from AnimatedContent(targetState = isExpanded) swaps shells during collapse and reanchors the bottom edge.",
            componentsSourceText.contains("AnimatedContent(\n            targetState = isExpanded,"),
        )
    }

    @Test
    fun collapseMotion_delaysCompactImeAdaptionUntilCollapseCompletes() {
        assertFalse(
            "Compact keyboard inset must not be applied directly from the raw expanded=false branch, or the sheet bottom jumps upward before collapse finishes.",
            componentsSourceText.contains(
                """
                            if (expanded) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier.padding(bottom = compactKeyboardInset)
                            },
                """.trimIndent(),
            ),
        )
    }
}
