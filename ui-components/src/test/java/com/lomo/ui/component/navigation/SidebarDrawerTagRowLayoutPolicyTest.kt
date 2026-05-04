package com.lomo.ui.component.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SidebarDrawer tag-tree row layout structure.
 * - Behavior focus: nested tag rows must reserve a dedicated trailing region for count and expand
 *   controls, while the label is constrained to a weighted single-line slot that ellipsizes before
 *   it reaches those trailing controls.
 * - Observable outcomes: the tag-tree source declares a dedicated row layout, uses a weighted label
 *   with TextOverflow.Ellipsis, and no longer delegates tag rows to NavigationDrawerItem's badge slot.
 * - Red phase: Fails before the fix because tag rows are rendered directly with NavigationDrawerItem,
 *   whose label/badge slots do not give this component ownership of the nested-row width budget.
 * - Excludes: Material3 token internals, drag-reorder behavior, LazyColumn recycling, and pixel-perfect rendering.
 */
class SidebarDrawerTagRowLayoutPolicyTest {
    private val tagTreeItemsSourceText =
        File("src/main/java/com/lomo/ui/component/navigation/SidebarDrawerTagTreeItems.kt").readText()

    @Test
    fun tagRows_useDedicatedLayoutWithWeightedEllipsizedLabel() {
        assertTrue(
            "Nested tag rows should have a dedicated layout so label width and trailing controls are measured in the same Row.",
            tagTreeItemsSourceText.contains("private fun SidebarTagRow("),
        )
        assertTrue(
            "The tag label must use a weighted slot so long nested names give the count and expand button reserved space.",
            tagTreeItemsSourceText.contains("Modifier.weight(1f)"),
        )
        assertTrue(
            "Long tag labels should ellipsize inside their slot instead of clipping or drawing into trailing controls.",
            tagTreeItemsSourceText.contains("overflow = TextOverflow.Ellipsis"),
        )
        assertTrue(
            "Count and expand controls should live in an explicit trailing region owned by the tag row.",
            tagTreeItemsSourceText.contains("private fun TagTreeTrailingContent("),
        )
        assertFalse(
            "NavigationDrawerItem's badge slot hides the width budget that nested tag rows need to prevent overlap.",
            tagTreeItemsSourceText.contains("NavigationDrawerItem("),
        )
    }
}
