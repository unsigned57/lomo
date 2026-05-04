package com.lomo.ui.component.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: sidebar visible tag-row expansion model.
 * - Behavior focus: expanded nested tags must become separate visible rows with stable depth metadata
 *   so the LazyColumn measures each row independently instead of drawing children inside one parent item.
 * - Observable outcomes: flattened row order and level values for collapsed, expanded, and nested-expanded paths.
 * - Red phase: Fails before the fix because tag children are composed recursively inside one lazy item,
 *   so there is no flattened row model for the LazyColumn to measure.
 * - Excludes: pixel rendering, drag gestures, Material3 row colors, and full drawer navigation.
 */
class SidebarDrawerVisibleTagRowsTest {
    @Test
    fun `expanded descendants are flattened into independent visible rows`() {
        val tagTree =
            buildTagTree(
                listOf(
                    SidebarTag("work", 4),
                    SidebarTag("work/android", 3),
                    SidebarTag("work/android/ui", 2),
                    SidebarTag("personal", 1),
                ),
                rootOrder = listOf("work", "personal"),
            )

        val rows =
            visibleTagRows(
                tagTree = tagTree,
                expandedNodePaths = setOf("work", "work/android"),
            )

        assertEquals(
            listOf(
                "work" to 0,
                "work/android" to 1,
                "work/android/ui" to 2,
                "personal" to 0,
            ),
            rows.map { row -> row.node.fullPath to row.level },
        )
    }

    @Test
    fun `collapsed descendants stay out of the measured row list`() {
        val tagTree =
            buildTagTree(
                listOf(
                    SidebarTag("work", 4),
                    SidebarTag("work/android", 3),
                    SidebarTag("work/android/ui", 2),
                ),
            )

        val rows =
            visibleTagRows(
                tagTree = tagTree,
                expandedNodePaths = setOf("work"),
            )

        assertEquals(
            listOf(
                "work" to 0,
                "work/android" to 1,
            ),
            rows.map { row -> row.node.fullPath to row.level },
        )
    }
}
