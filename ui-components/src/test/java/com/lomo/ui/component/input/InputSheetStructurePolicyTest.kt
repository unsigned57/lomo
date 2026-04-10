package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet structural layout policy for long-form toolbar and sheet motion host.
 * - Behavior focus: the editor toolbar must preserve fixed trailing actions while allowing tools to overflow horizontally, and the sheet scaffold must avoid IME-sensitive container size animation that exposes background during keyboard transitions.
 * - Observable outcomes: source contains dedicated scrollable tool-strip and fixed trailing-actions regions, uses LazyRow for tool overflow, and no longer applies animateContentSize to the sheet container.
 * - Red phase: Fails before the fix because the toolbar is a single Row that squeezes trailing actions and the scaffold animates container size on an IME-sensitive layer.
 * - Excludes: runtime IME OEM differences, pixel-perfect animation curves, and submit business logic.
 */
class InputSheetStructurePolicyTest {
    private val toolbarSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputEditorToolbarComponents.kt").readText()
    private val scaffoldSourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetScaffoldComponents.kt").readText()

    @Test
    fun toolbar_usesScrollableToolStripAndFixedTrailingActions() {
        assertTrue(
            "Input toolbar should expose a dedicated scrollable tool-strip region so leading tools can overflow without squeezing the trailing send action.",
            toolbarSourceText.contains("private fun InputToolbarScrollableTools("),
        )
        assertTrue(
            "Input toolbar should expose a dedicated fixed trailing-actions region so expand/send remain pinned.",
            toolbarSourceText.contains("internal fun InputToolbarTrailingActions("),
        )
        assertTrue(
            "Scrollable toolbar tools should be hosted by a LazyRow to support horizontal overflow.",
            toolbarSourceText.contains("LazyRow("),
        )
        assertFalse(
            "A weight-based spacer in the root toolbar row indicates the whole toolbar still relies on squeezing instead of splitting into scrollable and fixed regions.",
            toolbarSourceText.contains("Spacer(modifier = Modifier.weight(1f))"),
        )
    }

    @Test
    fun scaffold_usesDedicatedAnimatedSurfaceInsteadOfContainerSizeAnimation() {
        assertTrue(
            "Input sheet scaffold should introduce a dedicated animated surface layer so fullscreen transitions stay visually continuous during IME changes.",
            scaffoldSourceText.contains("private fun InputSheetAnimatedSurface("),
        )
        assertFalse(
            "animateContentSize on the sheet container makes IME-driven height changes reveal background during long-form transitions.",
            scaffoldSourceText.contains(".animateContentSize("),
        )
        assertFalse(
            "imePadding must not live on the AnimatedVisibility host because IME inset updates and visibility animation race each other.",
            scaffoldSourceText.contains(
                """
                AnimatedVisibility(
                            visible = isSheetVisible,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxSize()
                                    .imePadding()
                """.trimIndent(),
            ),
        )
    }
}
