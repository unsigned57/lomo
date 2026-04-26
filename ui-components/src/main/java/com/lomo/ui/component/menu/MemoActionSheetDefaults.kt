package com.lomo.ui.component.menu

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.lomo.ui.R
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal fun sortDefaultMemoActionSheetActions(
    actions: ImmutableList<MemoActionSheetAction>,
    rankedActionOrder: List<MemoActionId>,
): ImmutableList<MemoActionSheetAction> {
    val rankedIds = rankedActionOrder.distinct()
    if (rankedIds.isEmpty()) {
        return actions
    }
    val actionById = actions.associateBy(MemoActionSheetAction::id)
    return buildList {
        rankedIds.forEach { actionId ->
            actionById[actionId]?.let(::add)
        }
        addAll(actions.filterNot { action -> action.id != null && action.id in rankedIds })
    }.toImmutableList()
}

internal fun defaultPrimaryMemoActionIds(includeLanShare: Boolean): List<MemoActionId> =
    buildList {
        add(MemoActionId.COPY)
        add(MemoActionId.SHARE_IMAGE)
        add(MemoActionId.SHARE_TEXT)
        if (includeLanShare) {
            add(MemoActionId.LAN_SHARE)
        }
    }

@Composable
internal fun rememberDefaultMemoActionSheetActions(
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onLanShare: (() -> Unit)?,
    onTogglePin: (() -> Unit)?,
    isPinned: Boolean,
    onJump: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    showHistory: Boolean,
    showJump: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    actionAnchorForId: (MemoActionId) -> String? = { null },
): ImmutableList<MemoActionSheetAction> {
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
    val hasLanShareAction = onLanShare != null

    return remember(
        labels,
        handlers,
        isPinned,
        hasJumpAction,
        hasHistoryAction,
        hasPinAction,
        hasLanShareAction,
        actionAnchorForId,
    ) {
        buildList {
            addAll(primaryMemoActions(labels, handlers, includeLanShare = hasLanShareAction))
            addAll(
                optionalMemoActions(
                    labels = labels,
                    handlers = handlers,
                    isPinned = isPinned,
                    hasPinAction = hasPinAction,
                    hasJumpAction = hasJumpAction,
                    hasHistoryAction = hasHistoryAction,
                    actionAnchorForId = actionAnchorForId,
                ),
            )
            addAll(editingMemoActions(labels, handlers, actionAnchorForId))
        }.toImmutableList()
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
    onLanShare: (() -> Unit)?,
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
    includeLanShare: Boolean,
): List<MemoActionSheetAction> =
    defaultPrimaryMemoActionIds(includeLanShare = includeLanShare).map { actionId ->
        when (actionId) {
            MemoActionId.COPY ->
                MemoActionSheetAction(
                    id = MemoActionId.COPY,
                    icon = Icons.Outlined.ContentCopy,
                    label = labels.copy,
                    onClick = { handlers.onCopy.value() },
                )

            MemoActionId.SHARE_IMAGE ->
                MemoActionSheetAction(
                    id = MemoActionId.SHARE_IMAGE,
                    icon = Icons.Outlined.Share,
                    label = labels.shareImage,
                    onClick = { handlers.onShareImage.value() },
                )

            MemoActionId.SHARE_TEXT ->
                MemoActionSheetAction(
                    id = MemoActionId.SHARE_TEXT,
                    icon = Icons.AutoMirrored.Outlined.TextSnippet,
                    label = labels.shareText,
                    onClick = { handlers.onShareText.value() },
                )

            MemoActionId.LAN_SHARE ->
                MemoActionSheetAction(
                    id = MemoActionId.LAN_SHARE,
                    icon = Icons.Outlined.Wifi,
                    label = labels.lanShare,
                    onClick = { handlers.onLanShare.value?.invoke() },
                )

            else -> error("Unsupported primary memo action: $actionId")
        }
    }

private fun optionalMemoActions(
    labels: MemoActionLabels,
    handlers: MemoActionHandlers,
    isPinned: Boolean,
    hasPinAction: Boolean,
    hasJumpAction: Boolean,
    hasHistoryAction: Boolean,
    actionAnchorForId: (MemoActionId) -> String?,
): List<MemoActionSheetAction> =
    listOfNotNull(
        if (hasPinAction) {
            MemoActionSheetAction(
                id = MemoActionId.PIN,
                icon = Icons.Outlined.PushPin,
                label = labels.pin,
                benchmarkTag = actionAnchorForId(MemoActionId.PIN),
                onClick = { handlers.onTogglePin.value?.invoke() },
                isHighlighted = isPinned,
            )
        } else {
            null
        },
        if (hasJumpAction) {
            MemoActionSheetAction(
                id = MemoActionId.JUMP,
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = labels.jump,
                benchmarkTag = actionAnchorForId(MemoActionId.JUMP),
                onClick = { handlers.onJump.value?.invoke() },
            )
        } else {
            null
        },
        if (hasHistoryAction) {
            MemoActionSheetAction(
                id = MemoActionId.HISTORY,
                icon = Icons.Outlined.History,
                label = labels.history,
                benchmarkTag = actionAnchorForId(MemoActionId.HISTORY),
                onClick = { handlers.onHistory.value?.invoke() },
            )
        } else {
            null
        },
    )

private fun editingMemoActions(
    labels: MemoActionLabels,
    handlers: MemoActionHandlers,
    actionAnchorForId: (MemoActionId) -> String?,
): List<MemoActionSheetAction> =
    listOf(
        MemoActionSheetAction(
            id = MemoActionId.EDIT,
            icon = Icons.Outlined.Edit,
            label = labels.edit,
            benchmarkTag = actionAnchorForId(MemoActionId.EDIT),
            onClick = { handlers.onEdit.value() },
            dismissAfterClick = false,
        ),
        MemoActionSheetAction(
            id = MemoActionId.DELETE,
            icon = Icons.Outlined.Delete,
            label = labels.delete,
            benchmarkTag = actionAnchorForId(MemoActionId.DELETE),
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
    val onLanShare: State<(() -> Unit)?>,
    val onTogglePin: State<(() -> Unit)?>,
    val onJump: State<(() -> Unit)?>,
    val onHistory: State<(() -> Unit)?>,
    val onEdit: State<() -> Unit>,
    val onDelete: State<() -> Unit>,
)
