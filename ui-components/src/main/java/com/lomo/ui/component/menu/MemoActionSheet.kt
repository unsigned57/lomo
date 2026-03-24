package com.lomo.ui.component.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalOverscrollFactory
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.util.LocalAppHapticFeedback

enum class MemoActionHaptic {
    NONE,
    MEDIUM,
    HEAVY,
}

data class MemoActionSheetAction(
    val icon: ImageVector,
    val label: String,
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
    useHorizontalScroll: Boolean = true,
    showSwipeAffordance: Boolean = true,
    equalWidthActions: Boolean = false,
) {
    val haptic = LocalAppHapticFeedback.current
    val actionsScrollState = rememberScrollState()
    val sheetActions = actions ?: rememberDefaultMemoActionSheetActions(
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
private fun MemoActionRow(
    actions: List<MemoActionSheetAction>,
    actionsScrollState: androidx.compose.foundation.ScrollState,
    useHorizontalScroll: Boolean,
    equalWidthActions: Boolean,
    onDismiss: () -> Unit,
    onPerformHaptic: (MemoActionHaptic) -> Unit,
) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .let { base ->
                        if (useHorizontalScroll) {
                            base.horizontalScroll(actionsScrollState)
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
                        },
                    onClick = {
                        onPerformHaptic(action.haptic)
                        action.onClick()
                        if (action.dismissAfterClick) onDismiss()
                    },
                )
            }
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
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwipeEdgeIcon(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            enabled = canScrollBackward,
        )
        Spacer(modifier = Modifier.width(12.dp))
        BoxWithConstraints(
            modifier =
                Modifier
                    .width(96.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            val thumbWidth = 28.dp
            val maxTravel = maxWidth - thumbWidth
            Box(
                modifier =
                    Modifier
                        // Follow the real scroll position directly so the thumb does not replay
                        // a second catch-up animation when the row settles at either edge.
                        .offset { IntOffset(x = (maxTravel * clampedProgress).roundToPx(), y = 0) }
                        .width(thumbWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        SwipeEdgeIcon(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            enabled = canScrollForward,
        )
    }
}

@Composable
private fun SwipeEdgeIcon(
    icon: ImageVector,
    enabled: Boolean,
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
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = containerAlpha),
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp),
            )
        }
    }
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
