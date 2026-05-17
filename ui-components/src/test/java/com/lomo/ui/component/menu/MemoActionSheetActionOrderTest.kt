package com.lomo.ui.component.menu

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import kotlinx.collections.immutable.toImmutableList

/*
 * Test Contract:
 * - Unit under test: memo action ordering helpers in MemoActionSheet
 * - Behavior focus: stable application of persisted ranked action ids to default menu actions with duplicate filtering.
 * - Observable outcomes: reordered default action sequence based on persisted ranked ids and unchanged fallback order for missing ids.
 * - Red phase: Updated for the heat-based ranking contract; the helper must still honor a stable preferred-id order without duplicating actions.
 * - Excludes: Compose layout, drag physics, edge animations, and bottom-sheet host wiring.
 */
class MemoActionSheetActionOrderTest : UiComponentsFunSpec() {
    init {
        test("sortDefaultMemoActionSheetActions applies preferred order and preserves default fallback") {
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
            ).toImmutableList()

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = listOf(MemoActionId.HISTORY, MemoActionId.COPY),
            )

        (sorted.map(MemoActionSheetAction::id)) shouldBe (listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.EDIT))
        }
    }

    init {
        test("sortDefaultMemoActionSheetActions ignores duplicate ids in the preferred order") {
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
            ).toImmutableList()

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.HISTORY),
            )

        (sorted.map(MemoActionSheetAction::id)) shouldBe (listOf(MemoActionId.HISTORY, MemoActionId.COPY, MemoActionId.EDIT))
        }
    }

    init {
        test("sortDefaultMemoActionSheetActions applies stored order regardless of empty ranked list") {
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
            ).toImmutableList()

        val sorted =
            sortDefaultMemoActionSheetActions(
                actions = actions,
                rankedActionOrder = emptyList(),
            )

        (sorted.map(MemoActionSheetAction::id)) shouldBe (listOf(MemoActionId.COPY, MemoActionId.HISTORY))
        }
    }

    init {
        test("defaultPrimaryMemoActionIds omits lan share when the action is unavailable") {
        val actionIds = defaultPrimaryMemoActionIds(includeLanShare = false)

        (actionIds) shouldBe (listOf(MemoActionId.COPY, MemoActionId.SHARE_IMAGE, MemoActionId.SHARE_TEXT))
        (actionIds.contains(MemoActionId.LAN_SHARE)) shouldBe false
        }
    }
}
