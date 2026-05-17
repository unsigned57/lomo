package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: Input editor toolbar tool ordering policy.
 * - Behavior focus: undo and redo must remain behind the content-insertion tools, backfill must be part of the default toolbar, and persisted toolbar order must be normalized.
 * - Observable outcomes: resolved tool id ordering, relative positions, and filtering of duplicate/unknown persisted ids.
 * - Red phase: Fails before the fix because undo and redo are still placed at the front of the tool strip.
 * - Excludes: icon rendering, enablement state wiring, and trailing send/expand actions.
 */
class InputEditorToolbarOrderTest : UiComponentsFunSpec() {
    init {
        test("toolbar order places backfill before content formatting and undo redo behind underline") {
        val toolIds = inputToolbarToolIds()

        (toolIds) shouldBe (listOf("camera", "image", "record", "tag", "location", "backfill", "todo", "underline", "undo", "redo"))
        (toolIds.indexOf("location") < toolIds.indexOf("backfill")) shouldBe true
        (toolIds.indexOf("backfill") < toolIds.indexOf("todo")) shouldBe true
        (toolIds.indexOf("underline") < toolIds.indexOf("undo")) shouldBe true
        (toolIds.indexOf("undo") < toolIds.indexOf("redo")) shouldBe true
        }
    }

    init {
        test("persisted toolbar order is deduplicated and completed with default tools") {
        val toolIds =
            resolveInputToolbarToolIds(
                persistedOrder = listOf("backfill", "camera", "unknown", "backfill", "todo"),
            )

        (toolIds) shouldBe (listOf("backfill", "camera", "todo", "image", "record", "tag", "location", "underline", "undo", "redo"))
        }
    }

    init {
        test("backfill button enablement ignores submit state") {
        (resolveInputToolbarBackfillEnabled(
                toolbarEnabled = true,
                isBackfillEnabled = true,
            )) shouldBe true
        }
    }
}
