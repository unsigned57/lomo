package com.lomo.ui.component.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

enum class ActionItemHaptic {
    NONE,
    MEDIUM,
    HEAVY,
}

/** Visual tier of an action inside the shared action sheet. */
enum class ActionItemPlacement {
    /** Side-by-side filled capsules for short, decision-style sheets. */
    Capsule,

    /** Large icon-over-label tile in the top row; at most [ActionSheetTokens.MaxTileCount]. */
    Tile,

    /** Borderless list row; destructive rows are rendered last behind a divider. */
    Row,
}

data class ActionItemUi(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val placement: ActionItemPlacement,
    val benchmarkTag: String? = null,
    val isDestructive: Boolean = false,
    val isHighlighted: Boolean = false,
    val dismissAfterClick: Boolean = true,
    val haptic: ActionItemHaptic = ActionItemHaptic.MEDIUM,
)

internal data class ActionSheetPlan(
    val capsules: List<ActionItemUi>,
    val tiles: List<ActionItemUi>,
    val rows: List<ActionItemUi>,
    val destructiveRows: List<ActionItemUi>,
)

internal fun planActionSheet(actions: List<ActionItemUi>): ActionSheetPlan {
    val byPlacement = actions.groupBy(ActionItemUi::placement)
    val tiles = byPlacement[ActionItemPlacement.Tile].orEmpty()
    require(tiles.size <= ActionSheetTokens.MaxTileCount) {
        "Action sheet supports at most ${ActionSheetTokens.MaxTileCount} tiles, got ${tiles.size}: " +
            tiles.joinToString(transform = ActionItemUi::label)
    }
    val (destructiveRows, rows) = byPlacement[ActionItemPlacement.Row].orEmpty().partition(ActionItemUi::isDestructive)
    return ActionSheetPlan(
        capsules = byPlacement[ActionItemPlacement.Capsule].orEmpty(),
        tiles = tiles,
        rows = rows,
        destructiveRows = destructiveRows,
    )
}

@Composable
fun MemoActionSheet(
    state: MemoMenuState,
    actions: ImmutableList<ActionItemUi>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    benchmarkRootTag: String? = null,
) {
    val haptic = LocalAppHapticFeedback.current
    val plan = remember(actions) { planActionSheet(actions) }
    val perform: (ActionItemUi) -> Unit = { action -> action.perform(haptic, onDismiss) }

    Column(
        modifier =
        modifier
            .benchmarkAnchorRoot(benchmarkRootTag)
            .fillMaxWidth()
            .padding(horizontal = ActionSheetTokens.ContentHorizontalPadding)
            .padding(bottom = ActionSheetTokens.ContentBottomPadding),
    ) {
        if (plan.capsules.isNotEmpty()) {
            ActionCapsuleRow(actions = plan.capsules, onPerform = perform)
        }
        if (plan.tiles.isNotEmpty()) {
            ActionTileRow(actions = plan.tiles, onPerform = perform)
        }
        if (plan.rows.isNotEmpty() || plan.destructiveRows.isNotEmpty()) {
            if (plan.capsules.isNotEmpty() || plan.tiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(ActionSheetTokens.SectionSpacing))
            }
            ActionRowList(
                rows = plan.rows,
                destructiveRows = plan.destructiveRows,
                onPerform = perform,
            )
        }
        MemoMetaRow(
            state = state,
            modifier = Modifier.padding(top = ActionSheetTokens.SectionSpacing),
        )
    }
}

private fun ActionItemUi.perform(haptic: AppHapticFeedback, onDismiss: () -> Unit) {
    when (this.haptic) {
        ActionItemHaptic.NONE -> Unit
        ActionItemHaptic.MEDIUM -> haptic.medium()
        ActionItemHaptic.HEAVY -> haptic.heavy()
    }
    onClick()
    if (dismissAfterClick) onDismiss()
}

@Composable
private fun ActionRowList(
    rows: List<ActionItemUi>,
    destructiveRows: List<ActionItemUi>,
    onPerform: (ActionItemUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.Top) {
        rows.forEach { action -> ActionListRow(action = action, onClick = { onPerform(action) }) }
        if (destructiveRows.isNotEmpty()) {
            if (rows.isNotEmpty()) {
                ActionRowDivider()
            }
            destructiveRows.forEach { action -> ActionListRow(action = action, onClick = { onPerform(action) }) }
        }
    }
}
