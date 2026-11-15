package com.lomo.ui.component.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class ActionItemHaptic {
    NONE,
    MEDIUM,
    HEAVY,
}

/** Visual tier of an action inside the shared action sheet. */
enum class ActionItemPlacement {
    /** Side-by-side filled capsules for aligned secondary/decision actions. */
    Capsule,

    /** Compact icon-over-label tile in the top row; at most [ActionSheetTokens.MaxTileCount]. */
    Tile,
}

data class ActionItemUi(
    val icon: LomoIconSpec,
    val label: String,
    val onClick: () -> Unit,
    val placement: ActionItemPlacement,
    val benchmarkTag: String? = null,
    val isDestructive: Boolean = false,
    val isHighlighted: Boolean = false,
    val dismissAfterClick: Boolean = true,
    val haptic: ActionItemHaptic = ActionItemHaptic.MEDIUM,
    val subActions: ImmutableList<ActionItemUi> = persistentListOf(),
)

internal data class ActionSheetPlan(val capsules: List<ActionItemUi>, val tiles: List<ActionItemUi>)

internal fun planActionSheet(actions: List<ActionItemUi>): ActionSheetPlan {
    val byPlacement = actions.groupBy(ActionItemUi::placement)
    val tiles = byPlacement[ActionItemPlacement.Tile].orEmpty()
    require(tiles.size <= ActionSheetTokens.MaxTileCount) {
        "Action sheet supports at most ${ActionSheetTokens.MaxTileCount} tiles, got ${tiles.size}: " +
            tiles.joinToString(transform = ActionItemUi::label)
    }
    return ActionSheetPlan(
        capsules = byPlacement[ActionItemPlacement.Capsule].orEmpty(),
        tiles = tiles,
    )
}

@Composable
fun MemoActionSheet(
    actions: ImmutableList<ActionItemUi>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    state: MemoMenuState? = null,
    benchmarkRootTag: String? = null,
) {
    val haptic = LocalAppHapticFeedback.current
    val plan = remember(actions) { planActionSheet(actions) }
    val radialState = remember { RadialOverlayVisibilityState<String>() }
    val tileCentersInScreen = remember { mutableStateMapOf<String, Offset>() }
    val expandedParent =
        remember(plan, radialState.activeGroup) {
            radialState.activeGroup?.let { label ->
                plan.tiles.firstOrNull { tile -> tile.label == label && tile.subActions.isNotEmpty() }
            }
        }
    val perform: (ActionItemUi) -> Unit = { action ->
        if (action.subActions.isNotEmpty()) {
            haptic.medium()
            radialState.open(action.label)
        } else {
            radialState.completeImmediately()
            action.perform(haptic, onDismiss)
        }
    }
    Column(
        modifier =
        modifier
            .benchmarkAnchorRoot(benchmarkRootTag)
            .fillMaxWidth()
            .padding(horizontal = ActionSheetTokens.ContentHorizontalPadding)
            .padding(bottom = ActionSheetTokens.ContentBottomPadding),
    ) {
        if (plan.tiles.isNotEmpty()) {
            ActionTileRow(
                actions = plan.tiles,
                onTileCenterMeasured = { label, center -> tileCentersInScreen[label] = center },
                onPerform = perform,
            )
        }
        if (plan.capsules.isNotEmpty()) {
            if (plan.tiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(ActionSheetTokens.SectionSpacing))
            }
            ActionCapsuleRow(actions = plan.capsules, onPerform = perform)
        }
        if (state != null && state.createdTime.isNotBlank()) {
            if (plan.tiles.isNotEmpty() || plan.capsules.isNotEmpty()) {
                Spacer(modifier = Modifier.height(ActionSheetTokens.SectionSpacing))
            }
            MemoInfoCard(state = state)
        }
    }

    if (expandedParent != null) {
        RadialSubActionOverlay(
            subActions = expandedParent.subActions,
            visible = radialState.targetVisible,
            canInteract = radialState.canInteract,
            originInScreen = tileCentersInScreen[expandedParent.label],
            onSelectSubAction = { subAction ->
                radialState.completeImmediately()
                subAction.perform(haptic, onDismiss)
            },
            onDismiss = {
                if (radialState.requestDismiss() != null) {
                    haptic.medium()
                }
            },
            onDismissSettled = radialState::settleActiveDismiss,
        )
    }
}

private fun ActionItemUi.perform(feedback: AppHapticFeedback, onDismiss: () -> Unit) {
    when (haptic) {
        ActionItemHaptic.NONE -> Unit
        ActionItemHaptic.MEDIUM -> feedback.medium()
        ActionItemHaptic.HEAVY -> feedback.heavy()
    }
    if (dismissAfterClick) {
        onDismiss()
    }
    onClick()
}
