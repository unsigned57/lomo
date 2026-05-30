package com.lomo.ui.component.menu

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val DRAG_SCALE_FACTOR = 1.05f
internal const val DRAG_ALPHA = 0.92f

enum class ActionItemHaptic {
    NONE,
    MEDIUM,
    HEAVY,
}

data class ActionItemUi(
    val key: String? = null,
    val icon: ImageVector,
    val label: String,
    val benchmarkTag: String? = null,
    val onClick: () -> Unit,
    val isVisible: Boolean = true,
    val isEnabled: Boolean = true,
    val isDestructive: Boolean = false,
    val isHighlighted: Boolean = false,
    val dismissAfterClick: Boolean = true,
    val haptic: ActionItemHaptic = ActionItemHaptic.MEDIUM,
)

internal data class ActionItemRenderEntry(
    val action: ActionItemUi,
    val renderKey: String,
)

internal fun validateUniqueStableActionKeys(actions: List<ActionItemUi>) {
    val duplicateKeys =
        actions
            .asSequence()
            .mapNotNull(ActionItemUi::key)
            .groupingBy { key -> key }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
    require(duplicateKeys.isEmpty()) {
        "ActionItemUi stable keys must be unique: ${duplicateKeys.joinToString()}"
    }
}

internal fun toActionItemRenderEntries(actions: List<ActionItemUi>): List<ActionItemRenderEntry> {
    validateUniqueStableActionKeys(actions)
    val reservedRenderKeys = actions.mapNotNull(ActionItemUi::key).toMutableSet()
    var generatedKeyIndex = 0
    return actions.mapIndexed { index, action ->
        ActionItemRenderEntry(
            action = action,
            renderKey =
                action.key ?: nextGeneratedActionItemRenderKey(
                    reservedRenderKeys = reservedRenderKeys,
                    startIndex = generatedKeyIndex,
                ).also { generatedKey ->
                    reservedRenderKeys += generatedKey
                    generatedKeyIndex = generatedKey.removePrefix("no-key-").toInt() + 1
                },
        )
    }
}

private fun nextGeneratedActionItemRenderKey(
    reservedRenderKeys: Set<String>,
    startIndex: Int,
): String {
    var candidateIndex = startIndex
    while (true) {
        val candidate = "no-key-$candidateIndex"
        if (candidate !in reservedRenderKeys) {
            return candidate
        }
        candidateIndex++
    }
}

@Composable
fun MemoActionSheet(
    state: MemoMenuState,
    actions: ImmutableList<ActionItemUi>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionAutoReorderEnabled: Boolean = true,
    onActionInvoked: (String) -> Unit = {},
    onActionOrderChanged: (List<String>) -> Unit = {},
    benchmarkRootTag: String? = null,
    useHorizontalScroll: Boolean = true,
    showSwipeAffordance: Boolean = true,
    equalWidthActions: Boolean = false,
) {
    ActionSheetSurface(
        actions = actions,
        onDismiss = onDismiss,
        modifier = modifier,
        actionAutoReorderEnabled = actionAutoReorderEnabled,
        onActionInvoked = onActionInvoked,
        onActionOrderChanged = onActionOrderChanged,
        benchmarkRootTag = benchmarkRootTag,
        useHorizontalScroll = useHorizontalScroll,
        showSwipeAffordance = showSwipeAffordance,
        equalWidthActions = equalWidthActions,
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        MemoInfoCard(state = state)
    }
}

@Composable
fun ActionSheetSurface(
    actions: ImmutableList<ActionItemUi>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionAutoReorderEnabled: Boolean = true,
    onActionInvoked: (String) -> Unit = {},
    onActionOrderChanged: (List<String>) -> Unit = {},
    benchmarkRootTag: String? = null,
    useHorizontalScroll: Boolean = true,
    showSwipeAffordance: Boolean = true,
    equalWidthActions: Boolean = false,
    footerContent: @Composable () -> Unit = {},
) {
    val haptic = LocalAppHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lazyRowState = rememberLazyListState()
    val visibleActions = remember(actions) { actions.filter(ActionItemUi::isVisible).toImmutableList() }
    val reorderableActions = remember(visibleActions) { toActionItemRenderEntries(visibleActions).toMutableStateList() }
    val reorderableLazyRowState =
        rememberReorderableLazyListState(lazyRowState) { from, to ->
            val fromIndex = reorderableActions.indexOfFirst { it.renderKey == from.key }
            val toIndex = reorderableActions.indexOfFirst { it.renderKey == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                reorderableActions.add(toIndex, reorderableActions.removeAt(fromIndex))
            }
        }
    val showSwipeAffordanceIndicator by rememberShowSwipeAffordanceIndicator(
        lazyRowState = lazyRowState,
        useHorizontalScroll = useHorizontalScroll,
        showSwipeAffordance = showSwipeAffordance,
    )
    val swipeAffordanceProgress by rememberSwipeAffordanceProgress(lazyRowState)
    val canScrollBackward = lazyRowState.canScrollBackward
    val canScrollForward = lazyRowState.canScrollForward

    Column(
        modifier =
            modifier
                .benchmarkAnchorRoot(benchmarkRootTag)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
    ) {
        ActionItemRow(
            actions = reorderableActions,
            lazyRowState = lazyRowState,
            reorderableLazyRowState = reorderableLazyRowState,
            useHorizontalScroll = useHorizontalScroll,
            equalWidthActions = equalWidthActions,
            onDismiss = onDismiss,
            onPerformHaptic = { type -> performActionItemHaptic(haptic, type) },
            onActionInvoked = onActionInvoked,
            actionAutoReorderEnabled = actionAutoReorderEnabled,
            onActionOrderChanged = onActionOrderChanged,
        )

        if (showSwipeAffordanceIndicator) {
            val viewportWidthPx by rememberLazyRowViewportWidthPx(lazyRowState)
            SwipeAffordanceIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                progress = swipeAffordanceProgress,
                canScrollBackward = canScrollBackward,
                canScrollForward = canScrollForward,
                lazyRowState = lazyRowState,
                pageScrollDeltaPx = viewportWidthPx.coerceAtLeast(1),
                onScrollBackward = {
                    haptic.light()
                    scope.launch {
                        lazyRowState.animateScrollBy(-viewportWidthPx.toFloat())
                    }
                },
                onScrollForward = {
                    haptic.light()
                    scope.launch {
                        lazyRowState.animateScrollBy(viewportWidthPx.toFloat())
                    }
                },
            )
        }

        footerContent()
    }
}

internal enum class MemoActionRowLayoutMode {
    LAZY_ROW,
    EQUAL_WIDTH_STATIC,
}

@Composable
private fun ActionItemRow(
    actions: androidx.compose.runtime.snapshots.SnapshotStateList<ActionItemRenderEntry>,
    lazyRowState: androidx.compose.foundation.lazy.LazyListState,
    reorderableLazyRowState: sh.calvin.reorderable.ReorderableLazyListState,
    useHorizontalScroll: Boolean,
    equalWidthActions: Boolean,
    onDismiss: () -> Unit,
    onPerformHaptic: (ActionItemHaptic) -> Unit,
    onActionInvoked: (String) -> Unit,
    actionAutoReorderEnabled: Boolean,
    onActionOrderChanged: (List<String>) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    when (
        resolveMemoActionRowLayoutMode(
            equalWidthActions = equalWidthActions,
            useHorizontalScroll = useHorizontalScroll,
        )
    ) {
        MemoActionRowLayoutMode.LAZY_ROW ->
            LazyRow(
                state = lazyRowState,
                userScrollEnabled = useHorizontalScroll,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = actions,
                    key = ActionItemRenderEntry::renderKey,
                ) { entry ->
                    ReorderableItem(
                        state = reorderableLazyRowState,
                        key = entry.renderKey,
                    ) { isDragging ->
                        ReorderableActionChip(
                            action = entry.action,
                            isDragging = isDragging,
                            equalWidthActions = equalWidthActions,
                            haptic = haptic,
                            actionAutoReorderEnabled = actionAutoReorderEnabled,
                            onActionInvoked = onActionInvoked,
                            onActionOrderChanged = {
                                onActionOrderChanged(actions.mapNotNull { item -> item.action.key })
                            },
                            onPerformHaptic = onPerformHaptic,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }

        MemoActionRowLayoutMode.EQUAL_WIDTH_STATIC ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                val itemSpacing = 12.dp
                val actionWidth =
                    if (actions.size <= 1) {
                        maxWidth
                    } else {
                        (maxWidth - itemSpacing * (actions.size - 1)) / actions.size
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    actions.forEach { entry ->
                        EqualWidthActionChip(
                            action = entry.action,
                            modifier = Modifier.width(actionWidth),
                            actionAutoReorderEnabled = actionAutoReorderEnabled,
                            onActionInvoked = onActionInvoked,
                            onPerformHaptic = onPerformHaptic,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
    }
}

@Composable
private fun sh.calvin.reorderable.ReorderableCollectionItemScope.ReorderableActionChip(
    action: ActionItemUi,
    isDragging: Boolean,
    equalWidthActions: Boolean,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    actionAutoReorderEnabled: Boolean,
    onActionInvoked: (String) -> Unit,
    onActionOrderChanged: () -> Unit,
    onPerformHaptic: (ActionItemHaptic) -> Unit,
    onDismiss: () -> Unit,
) {
    val dragModifier =
        if (action.key != null && action.isEnabled) {
            Modifier.longPressDraggableHandle(
                onDragStarted = { haptic.heavy() },
                onDragStopped = { onActionOrderChanged() },
            )
        } else {
            Modifier
        }
    ActionChip(
        icon = action.icon,
        label = action.label,
        isDestructive = action.isDestructive,
        isHighlighted = action.isHighlighted,
        modifier =
            (if (equalWidthActions) Modifier else Modifier.width(92.dp))
                .benchmarkAnchor(action.benchmarkTag)
                .then(dragModifier)
                .graphicsLayer {
                    if (isDragging) {
                        scaleX = DRAG_SCALE_FACTOR
                        scaleY = DRAG_SCALE_FACTOR
                        alpha = DRAG_ALPHA
                    }
                },
        onClick = {
            onPerformHaptic(action.haptic)
            action.key
                ?.takeIf { actionAutoReorderEnabled }
                ?.let(onActionInvoked)
            action.onClick()
            if (action.dismissAfterClick) onDismiss()
        },
        enabled = action.isEnabled,
    )
}

@Composable
private fun MemoInfoCard(state: MemoMenuState) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoItem(
                label = stringResource(R.string.info_created),
                value = state.createdTime,
            )
            InfoItem(
                label = stringResource(R.string.info_characters),
                value = "${state.wordCount}",
                alignment = Alignment.End,
            )
        }
    }
}
