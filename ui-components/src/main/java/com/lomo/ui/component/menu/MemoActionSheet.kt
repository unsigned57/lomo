package com.lomo.ui.component.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    val sheetActions =
        actions ?: rememberDefaultMemoActionSheetActions(
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
    val showSwipeAffordanceIndicator by
        remember(actionsScrollState, useHorizontalScroll, showSwipeAffordance) {
            derivedStateOf {
                useHorizontalScroll && showSwipeAffordance && actionsScrollState.maxValue > 0
            }
        }
    val swipeAffordanceProgress by
        remember(actionsScrollState) {
            derivedStateOf {
                val maxValue = actionsScrollState.maxValue
                if (maxValue > 0) {
                    actionsScrollState.value.toFloat() / maxValue.toFloat()
                } else {
                    0f
                }
            }
        }
    val canScrollBackward by remember(actionsScrollState) { derivedStateOf { actionsScrollState.canScrollBackward } }
    val canScrollForward by remember(actionsScrollState) { derivedStateOf { actionsScrollState.canScrollForward } }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
    ) {
        // Quick Action Buttons Row (MD3 style chips)
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
            sheetActions.forEach { action ->
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
                        when (action.haptic) {
                            MemoActionHaptic.NONE -> Unit
                            MemoActionHaptic.MEDIUM -> haptic.medium()
                            MemoActionHaptic.HEAVY -> haptic.heavy()
                        }
                        action.onClick()
                        if (action.dismissAfterClick) onDismiss()
                    },
                )
            }
        }

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

        // Info Section - Modern Card Style
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
                InfoItem(label = stringResource(R.string.info_created), value = state.createdTime)
                InfoItem(label = stringResource(R.string.info_characters), value = "${state.wordCount}", alignment = Alignment.End)
            }
        }
    }
}

@Composable
private fun rememberDefaultMemoActionSheetActions(
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: () -> Unit,
    onTogglePin: (() -> Unit)?,
    isPinned: Boolean,
    onJump: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    showHistory: Boolean,
    showJump: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
): List<MemoActionSheetAction> {
    val onCopyState = rememberUpdatedState(onCopy)
    val onShareImageState = rememberUpdatedState(onShareImage)
    val onShareTextState = rememberUpdatedState(onShareText)
    val onLanShareState = rememberUpdatedState(onLanShare)
    val onTogglePinState = rememberUpdatedState(onTogglePin)
    val onJumpState = rememberUpdatedState(onJump)
    val onHistoryState = rememberUpdatedState(onHistory)
    val onEditState = rememberUpdatedState(onEdit)
    val onDeleteState = rememberUpdatedState(onDelete)

    val copyLabel = stringResource(R.string.action_copy)
    val shareImageLabel = stringResource(R.string.action_share)
    val shareTextLabel = stringResource(R.string.action_share_text)
    val lanShareLabel = stringResource(R.string.action_lan_share)
    val pinLabel = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin)
    val jumpLabel = stringResource(R.string.action_jump)
    val historyLabel = stringResource(R.string.action_history)
    val editLabel = stringResource(R.string.action_edit)
    val deleteLabel = stringResource(R.string.action_delete)
    val hasJumpAction = showJump && onJump != null
    val hasHistoryAction = showHistory && onHistory != null
    val hasPinAction = onTogglePin != null

    return remember(
        isPinned,
        hasJumpAction,
        hasHistoryAction,
        hasPinAction,
        copyLabel,
        shareImageLabel,
        shareTextLabel,
        lanShareLabel,
        pinLabel,
        jumpLabel,
        historyLabel,
        editLabel,
        deleteLabel,
    ) {
        buildList {
            add(
                MemoActionSheetAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = copyLabel,
                    onClick = { onCopyState.value() },
                    dismissAfterClick = true,
                    haptic = MemoActionHaptic.MEDIUM,
                ),
            )
            add(
                MemoActionSheetAction(
                    icon = Icons.Outlined.Share,
                    label = shareImageLabel,
                    onClick = { onShareImageState.value() },
                    dismissAfterClick = true,
                    haptic = MemoActionHaptic.MEDIUM,
                ),
            )
            add(
                MemoActionSheetAction(
                    icon = Icons.AutoMirrored.Outlined.TextSnippet,
                    label = shareTextLabel,
                    onClick = { onShareTextState.value() },
                    dismissAfterClick = true,
                    haptic = MemoActionHaptic.MEDIUM,
                ),
            )
            add(
                MemoActionSheetAction(
                    icon = Icons.Outlined.Wifi,
                    label = lanShareLabel,
                    onClick = { onLanShareState.value() },
                    dismissAfterClick = true,
                    haptic = MemoActionHaptic.MEDIUM,
                ),
            )
            if (hasPinAction) {
                add(
                    MemoActionSheetAction(
                        icon = Icons.Outlined.PushPin,
                        label = pinLabel,
                        onClick = { onTogglePinState.value?.invoke() },
                        isHighlighted = isPinned,
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.MEDIUM,
                    ),
                )
            }
            if (hasJumpAction) {
                add(
                    MemoActionSheetAction(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        label = jumpLabel,
                        onClick = { onJumpState.value?.invoke() },
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.MEDIUM,
                    ),
                )
            }
            if (hasHistoryAction) {
                add(
                    MemoActionSheetAction(
                        icon = Icons.Outlined.History,
                        label = historyLabel,
                        onClick = { onHistoryState.value?.invoke() },
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.MEDIUM,
                    ),
                )
            }
            add(
                MemoActionSheetAction(
                    icon = Icons.Outlined.Edit,
                    label = editLabel,
                    onClick = { onEditState.value() },
                    dismissAfterClick = false,
                    haptic = MemoActionHaptic.MEDIUM,
                ),
            )
            add(
                MemoActionSheetAction(
                    icon = Icons.Outlined.Delete,
                    label = deleteLabel,
                    onClick = { onDeleteState.value() },
                    isDestructive = true,
                    dismissAfterClick = false,
                    haptic = MemoActionHaptic.HEAVY,
                ),
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
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "menu_swipe_progress")

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
                        .offset { IntOffset(x = (maxTravel * animatedProgress).roundToPx(), y = 0) }
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
    val contentAlpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.38f, label = "menu_swipe_icon_alpha")
    val containerAlpha by animateFloatAsState(targetValue = if (enabled) 0.8f else 0.45f, label = "menu_swipe_icon_container")

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
