package com.lomo.ui.component.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.R
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class MemoActionHaptic {
    NONE,
    MEDIUM,
    HEAVY,
}

enum class MemoActionId(
    val storageKey: String,
) {
    COPY("copy"),
    SHARE_IMAGE("share_image"),
    SHARE_TEXT("share_text"),
    LAN_SHARE("lan_share"),
    PIN("pin"),
    JUMP("jump"),
    HISTORY("history"),
    EDIT("edit"),
    DELETE("delete"),
    ;

    companion object {
        fun fromStorageKey(raw: String): MemoActionId? =
            entries.firstOrNull { actionId -> actionId.storageKey == raw.trim() }
    }
}

data class MemoActionSheetAction(
    val id: MemoActionId? = null,
    val icon: ImageVector,
    val label: String,
    val benchmarkTag: String? = null,
    val onClick: () -> Unit,
    val isDestructive: Boolean = false,
    val isHighlighted: Boolean = false,
    val dismissAfterClick: Boolean = true,
    val haptic: MemoActionHaptic = MemoActionHaptic.MEDIUM,
)

@Composable
fun MemoActionSheet(
    state: MemoMenuState,
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onTogglePin: (() -> Unit)? = null,
    onJump: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    showHistory: Boolean = false,
    showJump: Boolean = false,
    actions: ImmutableList<MemoActionSheetAction>? = null,
    memoActionAutoReorderEnabled: Boolean = true,
    memoActionOrder: ImmutableList<String> = persistentListOf(),
    onActionInvoked: (MemoActionId) -> Unit = {},
    onActionOrderChanged: (List<MemoActionId>) -> Unit = {},
    benchmarkRootTag: String? = null,
    actionAnchorForId: (MemoActionId) -> String? = { null },
    useHorizontalScroll: Boolean = true,
    showSwipeAffordance: Boolean = true,
    equalWidthActions: Boolean = false,
) {
    val haptic = LocalAppHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lazyRowState = rememberLazyListState()
    val sheetActions =
        rememberResolvedMemoActionSheetActions(
            state = state,
            onCopy = onCopy,
            onShareImage = onShareImage,
            onShareText = onShareText,
            onLanShare = onLanShare,
            onTogglePin = onTogglePin,
            onJump = onJump,
            onEdit = onEdit,
            onDelete = onDelete,
            onHistory = onHistory,
            showHistory = showHistory,
            showJump = showJump,
            actions = actions,
            memoActionOrder = memoActionOrder,
            actionAnchorForId = actionAnchorForId,
        )
    val reorderableActions = remember(sheetActions) { sheetActions.toMutableStateList() }
    val reorderableLazyRowState =
        rememberReorderableLazyListState(lazyRowState) { from, to ->
            val fromIndex = reorderableActions.indexOfFirst { it.reorderKey == from.key }
            val toIndex = reorderableActions.indexOfFirst { it.reorderKey == to.key }
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
        MemoActionRow(
            actions = reorderableActions,
            lazyRowState = lazyRowState,
            reorderableLazyRowState = reorderableLazyRowState,
            useHorizontalScroll = useHorizontalScroll,
            equalWidthActions = equalWidthActions,
            onDismiss = onDismiss,
            onPerformHaptic = { type -> performMemoActionHaptic(haptic, type) },
            onActionInvoked = onActionInvoked,
            memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
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

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        MemoInfoCard(state = state)
    }
}

@Composable
private fun rememberLazyRowViewportWidthPx(
    lazyRowState: androidx.compose.foundation.lazy.LazyListState,
): androidx.compose.runtime.State<Int> =
    remember(lazyRowState) {
        androidx.compose.runtime.derivedStateOf {
            val layout = lazyRowState.layoutInfo
            (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
        }
    }

@Composable
private fun rememberResolvedMemoActionSheetActions(
    state: MemoMenuState,
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: (() -> Unit)?,
    onTogglePin: (() -> Unit)?,
    onJump: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: (() -> Unit)?,
    showHistory: Boolean,
    showJump: Boolean,
    actions: ImmutableList<MemoActionSheetAction>?,
    memoActionOrder: ImmutableList<String>,
    actionAnchorForId: (MemoActionId) -> String?,
): ImmutableList<MemoActionSheetAction> {
    val rankedActionOrder =
        remember(memoActionOrder) {
            memoActionOrder.mapNotNull(MemoActionId::fromStorageKey)
        }
    val defaultActions = rememberDefaultMemoActionSheetActions(
        onCopy = onCopy,
        onShareImage = onShareImage,
        onShareText = onShareText,
        onLanShare = onLanShare,
        onTogglePin = onTogglePin,
        isPinned = state.isPinned,
        onJump = onJump,
        onHistory = onHistory,
        showHistory = showHistory,
        showJump = showJump,
        onEdit = onEdit,
        onDelete = onDelete,
        actionAnchorForId = actionAnchorForId,
    )
    return actions
        ?: sortDefaultMemoActionSheetActions(
            actions = defaultActions,
            rankedActionOrder = rankedActionOrder,
        )
}

internal val MemoActionSheetAction.reorderKey: String
    get() = id?.storageKey ?: "no-id-$label"

@Composable
private fun MemoActionRow(
    actions: androidx.compose.runtime.snapshots.SnapshotStateList<MemoActionSheetAction>,
    lazyRowState: androidx.compose.foundation.lazy.LazyListState,
    reorderableLazyRowState: sh.calvin.reorderable.ReorderableLazyListState,
    useHorizontalScroll: Boolean,
    equalWidthActions: Boolean,
    onDismiss: () -> Unit,
    onPerformHaptic: (MemoActionHaptic) -> Unit,
    onActionInvoked: (MemoActionId) -> Unit,
    memoActionAutoReorderEnabled: Boolean,
    onActionOrderChanged: (List<MemoActionId>) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current

    LazyRow(
        state = lazyRowState,
        userScrollEnabled = useHorizontalScroll,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = actions,
            key = { it.reorderKey },
        ) { action ->
            ReorderableItem(
                state = reorderableLazyRowState,
                key = action.reorderKey,
            ) { isDragging ->
                ReorderableActionChip(
                    action = action,
                    isDragging = isDragging,
                    equalWidthActions = equalWidthActions,
                    haptic = haptic,
                    memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
                    onActionInvoked = onActionInvoked,
                    onActionOrderChanged = {
                        onActionOrderChanged(actions.mapNotNull(MemoActionSheetAction::id))
                    },
                    onPerformHaptic = onPerformHaptic,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun sh.calvin.reorderable.ReorderableCollectionItemScope.ReorderableActionChip(
    action: MemoActionSheetAction,
    isDragging: Boolean,
    equalWidthActions: Boolean,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    memoActionAutoReorderEnabled: Boolean,
    onActionInvoked: (MemoActionId) -> Unit,
    onActionOrderChanged: () -> Unit,
    onPerformHaptic: (MemoActionHaptic) -> Unit,
    onDismiss: () -> Unit,
) {
    val dragModifier =
        if (action.id != null) {
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
            action.id
                ?.takeIf { memoActionAutoReorderEnabled }
                ?.let(onActionInvoked)
            action.onClick()
            if (action.dismissAfterClick) onDismiss()
        },
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

@Composable
private fun SwipeAffordanceIndicator(
    progress: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    lazyRowState: androidx.compose.foundation.lazy.LazyListState,
    pageScrollDeltaPx: Int,
    onScrollBackward: () -> Unit,
    onScrollForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val draggableState =
        rememberDraggableState { delta ->
            val maxTravelPx =
                (trackWidthPx - with(density) { SwipeIndicatorThumbWidth.roundToPx() }).coerceAtLeast(1)
            val totalItemsCount = lazyRowState.layoutInfo.totalItemsCount
            val visibleItems = lazyRowState.layoutInfo.visibleItemsInfo
            val firstVisibleSize = visibleItems.firstOrNull()?.size ?: 0
            if (totalItemsCount <= 1 || firstVisibleSize <= 0) {
                return@rememberDraggableState
            }
            val visibleCount = visibleItems.size.coerceAtLeast(1)
            val hiddenItemCount = (totalItemsCount - visibleCount).coerceAtLeast(1)
            val totalScrollRangePx = hiddenItemCount * firstVisibleSize
            val scrollDelta = delta * totalScrollRangePx.toFloat() / maxTravelPx.toFloat()
            scope.launch { lazyRowState.scrollBy(scrollDelta) }
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwipeEdgeIcon(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            enabled = canScrollBackward,
            onClick = onScrollBackward,
            contentDescription = stringResource(R.string.cd_action_sheet_scroll_backward),
        )
        Spacer(modifier = Modifier.width(12.dp))
        BoxWithConstraints(
            modifier =
                Modifier
                    .width(112.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    .onSizeChanged { size -> trackWidthPx = size.width },
        ) {
            val maxTravel = maxWidth - SwipeIndicatorThumbWidth
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(x = (maxTravel * clampedProgress).roundToPx(), y = 0) }
                        .width(SwipeIndicatorThumbWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
                        .draggable(
                            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                            state = draggableState,
                            enabled = canScrollBackward || canScrollForward || pageScrollDeltaPx > 0,
                        ),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        SwipeEdgeIcon(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            enabled = canScrollForward,
            onClick = onScrollForward,
            contentDescription = stringResource(R.string.cd_action_sheet_scroll_forward),
        )
    }
}

@Composable
private fun SwipeEdgeIcon(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val contentAlpha by
        animateFloatAsState(
            targetValue = if (enabled) 1f else 0.38f,
            label = "menu_swipe_icon_alpha",
        )
    val containerAlpha by
        animateFloatAsState(
            targetValue = if (enabled) 0.8f else 0.45f,
            label = "menu_swipe_icon_container",
        )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = containerAlpha),
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val SwipeIndicatorThumbWidth = 28.dp

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
    isHighlighted: Boolean = false,
) {
    val containerColor =
        if (isDestructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        } else if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        }
    val contentColor =
        if (isDestructive) {
            MaterialTheme.colorScheme.onErrorContainer
        } else if (isHighlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Surface(
        onClick = onClick,
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(64.dp).widthIn(min = 72.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
