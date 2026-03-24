package com.lomo.ui.component.menu

import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.lomo.ui.R
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun rememberDefaultMemoActionSheetActions(
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
    val labels = rememberMemoActionLabels(isPinned)
    val handlers =
        rememberMemoActionHandlers(
            onCopy = onCopy,
            onShareImage = onShareImage,
            onShareText = onShareText,
            onLanShare = onLanShare,
            onTogglePin = onTogglePin,
            onJump = onJump,
            onHistory = onHistory,
            onEdit = onEdit,
            onDelete = onDelete,
        )
    val hasJumpAction = showJump && onJump != null
    val hasHistoryAction = showHistory && onHistory != null
    val hasPinAction = onTogglePin != null

    return remember(
        labels,
        handlers,
        isPinned,
        hasJumpAction,
        hasHistoryAction,
        hasPinAction,
    ) {
        buildList {
            addAll(primaryMemoActions(labels, handlers))
            addAll(
                optionalMemoActions(
                    labels = labels,
                    handlers = handlers,
                    isPinned = isPinned,
                    hasPinAction = hasPinAction,
                    hasJumpAction = hasJumpAction,
                    hasHistoryAction = hasHistoryAction,
                ),
            )
            addAll(editingMemoActions(labels, handlers))
        }
    }
}

@Composable
internal fun rememberShowSwipeAffordanceIndicator(
    actionsScrollState: ScrollState,
    useHorizontalScroll: Boolean,
    showSwipeAffordance: Boolean,
): State<Boolean> =
    remember(actionsScrollState, useHorizontalScroll, showSwipeAffordance) {
        derivedStateOf {
            useHorizontalScroll && showSwipeAffordance && actionsScrollState.maxValue > 0
        }
    }

@Composable
internal fun rememberSwipeAffordanceProgress(actionsScrollState: ScrollState): State<Float> =
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

internal fun performMemoActionHaptic(
    haptic: AppHapticFeedback,
    type: MemoActionHaptic,
) {
    when (type) {
        MemoActionHaptic.NONE -> Unit
        MemoActionHaptic.MEDIUM -> haptic.medium()
        MemoActionHaptic.HEAVY -> haptic.heavy()
    }
}

@Composable
private fun rememberMemoActionLabels(isPinned: Boolean): MemoActionLabels =
    MemoActionLabels(
        copy = stringResource(R.string.action_copy),
        shareImage = stringResource(R.string.action_share),
        shareText = stringResource(R.string.action_share_text),
        lanShare = stringResource(R.string.action_lan_share),
        pin = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
        jump = stringResource(R.string.action_jump),
        history = stringResource(R.string.action_history),
        edit = stringResource(R.string.action_edit),
        delete = stringResource(R.string.action_delete),
    )

@Composable
private fun rememberMemoActionHandlers(
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onJump: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
): MemoActionHandlers =
    MemoActionHandlers(
        onCopy = rememberUpdatedState(onCopy),
        onShareImage = rememberUpdatedState(onShareImage),
        onShareText = rememberUpdatedState(onShareText),
        onLanShare = rememberUpdatedState(onLanShare),
        onTogglePin = rememberUpdatedState(onTogglePin),
        onJump = rememberUpdatedState(onJump),
        onHistory = rememberUpdatedState(onHistory),
        onEdit = rememberUpdatedState(onEdit),
        onDelete = rememberUpdatedState(onDelete),
    )

private fun primaryMemoActions(
    labels: MemoActionLabels,
    handlers: MemoActionHandlers,
): List<MemoActionSheetAction> =
    listOf(
        MemoActionSheetAction(
            icon = Icons.Outlined.ContentCopy,
            label = labels.copy,
            onClick = { handlers.onCopy.value() },
        ),
        MemoActionSheetAction(
            icon = Icons.Outlined.Share,
            label = labels.shareImage,
            onClick = { handlers.onShareImage.value() },
        ),
        MemoActionSheetAction(
            icon = Icons.AutoMirrored.Outlined.TextSnippet,
            label = labels.shareText,
            onClick = { handlers.onShareText.value() },
        ),
        MemoActionSheetAction(
            icon = Icons.Outlined.Wifi,
            label = labels.lanShare,
            onClick = { handlers.onLanShare.value() },
        ),
    )

private fun optionalMemoActions(
    labels: MemoActionLabels,
    handlers: MemoActionHandlers,
    isPinned: Boolean,
    hasPinAction: Boolean,
    hasJumpAction: Boolean,
    hasHistoryAction: Boolean,
): List<MemoActionSheetAction> =
    listOfNotNull(
        if (hasPinAction) {
            MemoActionSheetAction(
                icon = Icons.Outlined.PushPin,
                label = labels.pin,
                onClick = { handlers.onTogglePin.value?.invoke() },
                isHighlighted = isPinned,
            )
        } else {
            null
        },
        if (hasJumpAction) {
            MemoActionSheetAction(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = labels.jump,
                onClick = { handlers.onJump.value?.invoke() },
            )
        } else {
            null
        },
        if (hasHistoryAction) {
            MemoActionSheetAction(
                icon = Icons.Outlined.History,
                label = labels.history,
                onClick = { handlers.onHistory.value?.invoke() },
            )
        } else {
            null
        },
    )

private fun editingMemoActions(
    labels: MemoActionLabels,
    handlers: MemoActionHandlers,
): List<MemoActionSheetAction> =
    listOf(
        MemoActionSheetAction(
            icon = Icons.Outlined.Edit,
            label = labels.edit,
            onClick = { handlers.onEdit.value() },
            dismissAfterClick = false,
        ),
        MemoActionSheetAction(
            icon = Icons.Outlined.Delete,
            label = labels.delete,
            onClick = { handlers.onDelete.value() },
            isDestructive = true,
            dismissAfterClick = false,
            haptic = MemoActionHaptic.HEAVY,
        ),
    )

private data class MemoActionLabels(
    val copy: String,
    val shareImage: String,
    val shareText: String,
    val lanShare: String,
    val pin: String,
    val jump: String,
    val history: String,
    val edit: String,
    val delete: String,
)

private data class MemoActionHandlers(
    val onCopy: State<() -> Unit>,
    val onShareImage: State<() -> Unit>,
    val onShareText: State<() -> Unit>,
    val onLanShare: State<() -> Unit>,
    val onTogglePin: State<(() -> Unit)?>,
    val onJump: State<(() -> Unit)?>,
    val onHistory: State<(() -> Unit)?>,
    val onEdit: State<() -> Unit>,
    val onDelete: State<() -> Unit>,
)
