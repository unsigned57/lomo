package com.lomo.ui.component.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberOverscrollEffect
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onLanShare: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
    onJump: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onHistory: (() -> Unit)? = null,
    showHistory: Boolean = false,
    showJump: Boolean = false,
    actions: List<MemoActionSheetAction>? = null,
    memoActionAutoReorderEnabled: Boolean = true,
    memoActionOrder: List<String> = emptyList(),
    onActionInvoked: (MemoActionId) -> Unit = {},
    benchmarkRootTag: String? = null,
    actionAnchorForId: (MemoActionId) -> String? = { null },
    useHorizontalScroll: Boolean = true,
    showSwipeAffordance: Boolean = true,
    equalWidthActions: Boolean = false,
) {
    val haptic = LocalAppHapticFeedback.current
    val scope = rememberCoroutineScope()
    val actionsScrollState = rememberScrollState()
    var actionRowViewportWidthPx by remember { mutableIntStateOf(0) }
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
            memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
            memoActionOrder = memoActionOrder,
            actionAnchorForId = actionAnchorForId,
        )
    val showSwipeAffordanceIndicator by rememberShowSwipeAffordanceIndicator(
        actionsScrollState = actionsScrollState,
        useHorizontalScroll = useHorizontalScroll,
        showSwipeAffordance = showSwipeAffordance,
    )
    val swipeAffordanceProgress by rememberSwipeAffordanceProgress(actionsScrollState)
    val canScrollBackward = actionsScrollState.canScrollBackward
    val canScrollForward = actionsScrollState.canScrollForward

    Column(
        modifier =
            Modifier
                .benchmarkAnchorRoot(benchmarkRootTag)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
    ) {
        MemoActionRow(
            actions = sheetActions,
            actionsScrollState = actionsScrollState,
            useHorizontalScroll = useHorizontalScroll,
            equalWidthActions = equalWidthActions,
            onDismiss = onDismiss,
            onPerformHaptic = { type -> performMemoActionHaptic(haptic, type) },
            onActionInvoked = onActionInvoked,
            memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
            onViewportWidthChanged = { width -> actionRowViewportWidthPx = width },
        )

        if (showSwipeAffordanceIndicator) {
            SwipeAffordanceIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                progress = swipeAffordanceProgress,
                canScrollBackward = canScrollBackward,
                canScrollForward = canScrollForward,
                actionsScrollState = actionsScrollState,
                pageScrollDeltaPx = actionRowViewportWidthPx.coerceAtLeast(1),
                onScrollBackward = {
                    haptic.light()
                    scope.launch {
                        actionsScrollState.animateScrollTo(
                            (actionsScrollState.value - actionRowViewportWidthPx)
                                .coerceAtLeast(0),
                        )
                    }
                },
                onScrollForward = {
                    haptic.light()
                    scope.launch {
                        actionsScrollState.animateScrollTo(
                            (actionsScrollState.value + actionRowViewportWidthPx)
                                .coerceAtMost(actionsScrollState.maxValue),
                        )
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
private fun rememberResolvedMemoActionSheetActions(
    state: MemoMenuState,
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onJump: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: (() -> Unit)?,
    showHistory: Boolean,
    showJump: Boolean,
    actions: List<MemoActionSheetAction>?,
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: List<String>,
    actionAnchorForId: (MemoActionId) -> String?,
): List<MemoActionSheetAction> {
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
            autoReorderEnabled = memoActionAutoReorderEnabled,
        )
}

@Composable
private fun MemoActionRow(
    actions: List<MemoActionSheetAction>,
    actionsScrollState: androidx.compose.foundation.ScrollState,
    useHorizontalScroll: Boolean,
    equalWidthActions: Boolean,
    onDismiss: () -> Unit,
    onPerformHaptic: (MemoActionHaptic) -> Unit,
    onActionInvoked: (MemoActionId) -> Unit,
    memoActionAutoReorderEnabled: Boolean,
    onViewportWidthChanged: (Int) -> Unit,
) {
    val overscrollEffect = rememberOverscrollEffect()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onViewportWidthChanged(size.width) }
                .let { base ->
                    if (useHorizontalScroll) {
                        base.horizontalScroll(
                            state = actionsScrollState,
                            overscrollEffect = overscrollEffect,
                        )
                    } else {
                        base
                    }
                }.padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        actions.forEach { action ->
            ActionChip(
                icon = action.icon,
                label = action.label,
                isDestructive = action.isDestructive,
                isHighlighted = action.isHighlighted,
                modifier =
                    if (equalWidthActions) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.width(92.dp)
                    }.benchmarkAnchor(action.benchmarkTag),
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
    }
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
    actionsScrollState: androidx.compose.foundation.ScrollState,
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
            val maxScroll = actionsScrollState.maxValue
            val maxTravelPx =
                (trackWidthPx - with(density) { SwipeIndicatorThumbWidth.roundToPx() }).coerceAtLeast(1)
            if (maxScroll <= 0 || maxTravelPx <= 0) {
                return@rememberDraggableState
            }
            val scrollDelta = delta * maxScroll / maxTravelPx
            scope.launch { actionsScrollState.scrollBy(scrollDelta) }
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
                        // Follow the real scroll position directly so the thumb does not replay
                        // a second catch-up animation when the row settles at either edge.
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

// Predictive edge preview has been retired. Menu boundary feedback now comes
// exclusively from Compose's stock overscroll effect.
internal fun calculateMenuEdgePreviewOffset(
    dragDeltaX: Float,
    scrollValue: Int,
    maxScroll: Int,
    viewportWidthPx: Int,
): Float {
    val retiredPreviewSeed =
        dragDeltaX +
            (scrollValue.toFloat() * 0f) +
            (maxScroll.toFloat() * 0f) +
            (viewportWidthPx.toFloat() * 0f)
    return retiredPreviewSeed.coerceIn(0f, 0f)
}

internal fun calculateMenuEdgePreviewFlingOffset(
    velocityX: Float,
    scrollValue: Int,
    maxScroll: Int,
    viewportWidthPx: Int,
    retainedPreviewOffsetPx: Float = 0f,
): Float {
    val retiredPreviewSeed =
        velocityX +
            retainedPreviewOffsetPx +
            (scrollValue.toFloat() * 0f) +
            (maxScroll.toFloat() * 0f) +
            (viewportWidthPx.toFloat() * 0f)
    return retiredPreviewSeed.coerceIn(0f, 0f)
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
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
