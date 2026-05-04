package com.lomo.ui.component.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SidebarDrawer tag-tree expansion policy.
 * - Behavior focus: expanding nested tags in the drawer should produce independent LazyColumn rows
 *   instead of drawing child rows inside the parent lazy item.
 * - Observable outcomes: sidebar tags are sourced from visibleTagRows and tag-tree expansion no
 *   longer imports AnimatedVisibility/expandVertically/shrinkVertically or composes TagTreeChildren.
 * - Red phase: Fails before the fix because TagTreeChildren wraps child rows in AnimatedVisibility
 *   with vertical expand/shrink transitions, matching the user-visible overlap during expansion.
 * - Excludes: drag-reorder behavior, label truncation, full drawer navigation wiring, and animation easing.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract tightened after overlap persisted without height animation.
 * - Old behavior/assertion being replaced: the test accepted a recursive child Column inside the
 *   parent lazy item as long as AnimatedVisibility was removed.
 * - Why old assertion is no longer correct: that still lets a root lazy item change height and draw
 *   descendants as one measured item; the reported overlap requires child rows to be measured independently.
 * - Coverage preserved by: the test still rejects animated height transitions and now also rejects the
 *   recursive child composable path.
 * - Why this is not fitting the test to the implementation: the assertion expresses the visible layout
 *   contract that each expanded tag row must occupy its own LazyColumn slot.
 */
/*
 * Test Change Justification:
 * - Reason category: architectural source ownership adjustment.
 * - Old behavior/assertion being replaced: visibleTagRows was required to be called inside
 *   SidebarDrawerTagTreeItems.kt.
 * - Why old assertion is no longer correct: LazyListMotion needs the flattened row keys before
 *   LazyColumn item emission, so SidebarDrawer.kt now owns the flattened row list and passes it to
 *   the item emitter.
 * - Coverage preserved by: the combined source assertion still requires visibleTagRows and still
 *   rejects AnimatedVisibility, expandVertically, shrinkVertically, and recursive TagTreeChildren.
 * - Why this is not fitting the test to the implementation: the product contract is unchanged;
 *   only the file that computes the flattened rows moved to support shared motion coordination.
 */
class SidebarDrawerTagExpansionPolicyTest {
    private val combinedSource =
        listOf(
            File("src/main/java/com/lomo/ui/component/navigation/SidebarDrawer.kt"),
            File("src/main/java/com/lomo/ui/component/navigation/SidebarDrawerTagTreeItems.kt"),
        ).joinToString(separator = "\n") { file -> file.readText() }
    private val itemSource =
        File("src/main/java/com/lomo/ui/component/navigation/SidebarDrawerTagTreeItems.kt").readText()

    @Test
    fun `tag expansion emits flattened rows without vertical height animation`() {
        assertTrue(
            "Expanded tag rows should be flattened before LazyColumn item emission.",
            combinedSource.contains("visibleTagRows("),
        )
        assertFalse(
            "AnimatedVisibility height transitions can temporarily overlap nested tag rows during drawer expansion.",
            itemSource.contains("AnimatedVisibility("),
        )
        assertFalse(
            "expandVertically should not drive tag-tree expansion.",
            itemSource.contains("expandVertically("),
        )
        assertFalse(
            "shrinkVertically should not drive tag-tree collapse.",
            itemSource.contains("shrinkVertically("),
        )
        assertFalse(
            "Recursive child composition keeps descendants inside one parent lazy item.",
            itemSource.contains("TagTreeChildren("),
        )
    }
}
