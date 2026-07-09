package com.lomo.ui.component.navigation

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/*
 * Test Contract:
 * - Unit under test: applyReorderableTagMove (pure guard on reorder callback)
 * - Behavior focus: The sidebar LazyColumn contains non-tag items (header, stats, heatmap,
 *   destination rows, divider) alongside tag rows. The library's onMove fires for any
 *   intended move; this guard must only permit swaps where both endpoints are tag root
 *   fullPaths present in the tree, and must reject moves where either key is null, non-String,
 *   or points to a non-tag item.
 * - Observable outcomes: returned Boolean (applied?) and the SnapshotStateList mutation.
 * - Red phase: Verified by asserting move operations fail when logic is stripped.
 * - Excludes: library drag dispatch, LazyList measurement, pointer input.
 */
class ApplyReorderableTagMoveTest : UiComponentsFunSpec() {
    private fun tree(vararg paths: String): SnapshotStateList<TagNode> =
        paths.map { path -> TagNode(name = path, fullPath = path) }.toMutableStateList()

    init {
        test("swaps two tags by fullPath and reports applied") {
        val tagTree = tree("work", "personal", "travel")

        val applied =
            applyReorderableTagMove(
                tagTree = tagTree,
                fromKey = "travel",
                toKey = "work",
            )

        (applied) shouldBe true
        (tagTree.map { it.fullPath }) shouldBe (listOf("travel", "work", "personal"))
        }
    }

    init {
        test("rejects move when from key is not in tree") {
        val tagTree = tree("work", "personal")

        val applied =
            applyReorderableTagMove(
                tagTree = tagTree,
                fromKey = "sidebar_tags_header",
                toKey = "work",
            )

        (applied) shouldBe false
        (tagTree.map { it.fullPath }) shouldBe (listOf("work", "personal"))
        }
    }

    init {
        test("rejects move when to key is not in tree") {
        val tagTree = tree("work", "personal")

        val applied =
            applyReorderableTagMove(
                tagTree = tagTree,
                fromKey = "work",
                toKey = "sidebar_tags_header",
            )

        (applied) shouldBe false
        (tagTree.map { it.fullPath }) shouldBe (listOf("work", "personal"))
        }
    }

    init {
        test("rejects move when either key is null") {
        val tagTree = tree("work", "personal")

        (applyReorderableTagMove(tagTree, fromKey = null, toKey = "work")) shouldBe false
        (applyReorderableTagMove(tagTree, fromKey = "work", toKey = null)) shouldBe false
        (tagTree.map { it.fullPath }) shouldBe (listOf("work", "personal"))
        }
    }

    init {
        test("no-op when from equals to") {
        val tagTree = tree("work", "personal")

        val applied =
            applyReorderableTagMove(
                tagTree = tagTree,
                fromKey = "work",
                toKey = "work",
            )

        (applied) shouldBe false
        (tagTree.map { it.fullPath }) shouldBe (listOf("work", "personal"))
        }
    }
}
