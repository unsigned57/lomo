package com.lomo.ui.component.navigation

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given expanded nested tags, when visible rows are flattened, then descendants retain independent rows and
 *   depth metadata.
 * - Given collapsed ancestors, when rows are flattened, then their hidden descendants are excluded.
 * - Unit under test: sidebar visible tag-row expansion model.
 * - Capability: expanded nested tags must become separate visible rows with stable depth metadata
 *   so the LazyColumn measures each row independently instead of drawing children inside one parent item.
 * - Observable outcomes: flattened row order and level values for collapsed, expanded, and nested-expanded paths.
 * - TDD proof: Fails before the fix because tag children are composed recursively inside one lazy item,
 *   so there is no flattened row model for the LazyColumn to measure.
 * - Excludes: pixel rendering, drag gestures, Material3 row colors, and full drawer navigation.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for SidebarDrawerVisibleTagRows.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including expanded descendants are flattened into
 * independent visible rows; collapsed descendants stay out of the measured row list.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SidebarDrawerVisibleTagRowsTest : UiComponentsFunSpec() {
    init {
        test("expanded descendants are flattened into independent visible rows") {
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

            (rows.map { row -> row.node.fullPath to row.level }) shouldBe (
                listOf(
                    "work" to 0,
                    "work/android" to 1,
                    "work/android/ui" to 2,
                    "personal" to 0,
                )
                )
        }
    }

    init {
        test("collapsed descendants stay out of the measured row list") {
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

            (rows.map { row -> row.node.fullPath to row.level }) shouldBe (
                listOf(
                    "work" to 0,
                    "work/android" to 1,
                )
                )
        }
    }
}
