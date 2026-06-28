package com.lomo.ui.component.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.lomo.ui.R
import com.lomo.ui.benchmark.benchmarkAnchor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun rememberLazyRowViewportWidthPx(
    lazyRowState: androidx.compose.foundation.lazy.LazyListState,
): androidx.compose.runtime.State<Int> =
    remember(lazyRowState) {
        androidx.compose.runtime.derivedStateOf {
            val layout = lazyRowState.layoutInfo
            (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
        }
    }

@Composable
internal fun EqualWidthActionChip(
    action: ActionItemUi,
    actionAutoReorderEnabled: Boolean,
    onActionInvoked: (String) -> Unit,
    onPerformHaptic: (ActionItemHaptic) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    ActionChip(
        icon = action.icon,
        label = action.label,
        isDestructive = action.isDestructive,
        isHighlighted = action.isHighlighted,
        modifier = modifier.benchmarkAnchor(action.benchmarkTag),
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
internal fun SwipeAffordanceIndicator(
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
                (trackWidthPx - with(density) { ActionSheetTokens.SwipeThumbWidth.roundToPx() }).coerceAtLeast(1)
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
        Spacer(modifier = Modifier.width(ActionSheetTokens.SwipeEdgeSpacing))
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier =
                Modifier
                    .width(ActionSheetTokens.SwipeTrackWidth)
                    .height(ActionSheetTokens.SwipeTrackHeight)
                    .clip(ActionSheetTokens.SwipeShape)
                    .background(ActionSheetTokens.swipeTrackColor(MaterialTheme.colorScheme))
                    .onSizeChanged { size -> trackWidthPx = size.width },
        ) {
            val maxTravel = maxWidth - ActionSheetTokens.SwipeThumbWidth
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(x = (maxTravel * clampedProgress).roundToPx(), y = 0) }
                        .width(ActionSheetTokens.SwipeThumbWidth)
                        .fillMaxHeight()
                        .clip(ActionSheetTokens.SwipeShape)
                        .background(ActionSheetTokens.swipeThumbColor(MaterialTheme.colorScheme))
                        .draggable(
                            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                            state = draggableState,
                            enabled = canScrollBackward || canScrollForward || pageScrollDeltaPx > 0,
                        ),
            )
        }
        Spacer(modifier = Modifier.width(ActionSheetTokens.SwipeEdgeSpacing))
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
        shape = ActionSheetTokens.SwipeShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = containerAlpha),
    ) {
        Box(
            modifier = Modifier.size(ActionSheetTokens.SwipeEdgeButtonSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(ActionSheetTokens.SwipeEdgeIconSize),
            )
        }
    }
}

@Composable
internal fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
    isHighlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val containerColor =
        ActionSheetTokens.actionChipContainerColor(
            colorScheme = MaterialTheme.colorScheme,
            isDestructive = isDestructive,
            isHighlighted = isHighlighted,
        )
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
        enabled = enabled,
        color = containerColor,
        shape = ActionSheetTokens.ActionChipShape,
        modifier =
            modifier
                .height(ActionSheetTokens.ActionChipHeight)
                .widthIn(min = ActionSheetTokens.ActionChipMinWidth),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(ActionSheetTokens.ActionChipPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(ActionSheetTokens.ActionChipIconSize),
            )
            Spacer(modifier = Modifier.height(ActionSheetTokens.ActionChipIconLabelSpacing))
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
internal fun InfoItem(
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
        Spacer(modifier = Modifier.height(ActionSheetTokens.InfoItemTextSpacing))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
