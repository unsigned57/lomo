package com.lomo.ui.component.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo action ordering helpers in MemoActionSheet
 * - Behavior focus: stable application of persisted ranked action ids to default menu actions with duplicate filtering.
 * - Observable outcomes: reordered default action sequence based on persisted ranked ids and unchanged fallback order for missing ids.
 * - Red phase: Updated for the heat-based ranking contract; the helper must still honor a stable preferred-id order without duplicating actions.
 * - Excludes: Compose layout, drag physics, edge animations, and bottom-sheet host wiring.
 */
class MemoActionSheetActionOrderTest {
    @Test
    fun `sortDefaultMemoActionSheetActions applies preferred order and preserves default fallback`() {
        val actions =
            listOf(
                MemoActionSheetAction(
                    id = MemoActionId.COPY,
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = {},
                ),
                MemoActionSheetAction(
                    id = MemoActionId.HISTORY,
                    icon = Icons.Outlined.History,
                    label = "History",
                    onClick = {},
                ),
                MemoActionSheetAction(
                    id = MemoActionId.EDIT,
                    icon = Icons.Outlined.Edit,
                    label = "Edit",
                    onClick = {},
                ),
            )

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = listOf(MemoActionId.HISTORY, MemoActionId.COPY),
                autoReorderEnabled = true,
            )

        assertEquals(
            listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.EDIT),
            sorted.map(MemoActionSheetAction::id),
        )
    }

    @Test
    fun `sortDefaultMemoActionSheetActions ignores duplicate ids in the preferred order`() {
        val actions =
            listOf(
                MemoActionSheetAction(
                    id = MemoActionId.COPY,
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = {},
                ),
                MemoActionSheetAction(
                    id = MemoActionId.HISTORY,
                    icon = Icons.Outlined.History,
                    label = "History",
                    onClick = {},
                ),
                MemoActionSheetAction(
                    id = MemoActionId.EDIT,
                    icon = Icons.Outlined.Edit,
                    label = "Edit",
                    onClick = {},
                ),
            )

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.HISTORY),
                autoReorderEnabled = true,
            )

        assertEquals(
            listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.EDIT),
            sorted.map(MemoActionSheetAction::id),
        )
    }

    @Test
    fun `sortDefaultMemoActionSheetActions keeps default order when auto reorder is disabled`() {
        val actions =
            listOf(
                MemoActionSheetAction(
                    id = MemoActionId.COPY,
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = {},
                ),
                MemoActionSheetAction(
                    id = MemoActionId.HISTORY,
                    icon = Icons.Outlined.History,
                    label = "History",
                    onClick = {},
                ),
            )

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = listOf(MemoActionId.HISTORY),
                autoReorderEnabled = false,
            )

        assertEquals(
            listOf(MemoActionId.COPY, MemoActionId.HISTORY),
            sorted.map(MemoActionSheetAction::id),
        )
    }
}
