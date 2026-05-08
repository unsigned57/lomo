package com.lomo.ui.component.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: input editor toolbar click/drag modifier contract.
 * - Behavior focus: reorderable long-press drag must not wrap toolbar icon buttons in a parent pointer
 *   handler that swallows normal clicks such as Backfill.
 * - Observable outcomes: source-level contract that toolbar icon buttons accept a modifier and the drag
 *   handle is applied to the clickable icon button itself.
 * - Red phase: Fails before the fix because the reorder modifier is installed on a parent Box around the
 *   IconButton, so Backfill taps can be intercepted before the click callback runs.
 * - Excludes: Android gesture dispatch internals, Compose rendering, and the DatePicker dialog.
 */
class InputEditorToolbarClickDragContractTest {
    @Test
    fun `toolbar icon button owns the reorder handle modifier without a pointer wrapper`() {
        val source =
            File("src/main/java/com/lomo/ui/component/input/InputEditorToolbarComponents.kt")
                .readText()

        assertTrue(
            "InputToolbarIconButton should expose a modifier so the reorder handle can be installed on the clickable node.",
            source.contains("modifier: Modifier = Modifier"),
        )
        assertTrue(
            "IconButton should apply the provided modifier directly to the clickable node.",
            source.contains("modifier = modifier,"),
        )
        assertFalse(
            "The drag handle should not sit on a parent Box around the IconButton because it can swallow taps.",
            source.contains("Box(\n                    modifier =\n                        dragModifier.graphicsLayer"),
        )
    }
}
